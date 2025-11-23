import { logger } from '../../utils/logger';
import { CACHE_TTL_MS, CACHE_VERSION, NO_LYRICS_TEXT, PROVIDER_PRIORITY, type ProviderKey } from './constants';
import type { LyricsRequest, LyricsResponse, LyricSourceResult, SegmentMap, LyricLine } from './types';
import { loadBlyricsLineSync, loadBlyricsRichSync, type BLyricsResult } from './providers/bLyricsProvider';
import { loadLrclibPlain, loadLrclibSynced, type LrclibResult } from './providers/lrclibProvider';
import { loadYoutubeCaptions } from './providers/ytCaptionsProvider';
import { loadYoutubeLyrics } from './providers/ytLyricsProvider';
import type { WebSocketManager } from '../WebSocketManager';
import { calculateSimilarity } from './lrcUtils';
import { PlayerTelemetryState } from '../../models/PlayerTelemetryState';
import { SegmentMapService } from './SegmentMapService';
import { AppConfig } from '../../config/appConfig';

interface CacheEntry {
  version: string;
  expiresAt: number;
  payload: LyricsResponse;
}

interface ProviderSharedState {
  blyrics: Map<string, BLyricsResult | null>;
  lrclib: Map<string, LrclibResult | null>;
}

export class LyricsService {
  private cache = new Map<string, CacheEntry>();
  private webSocketManager: WebSocketManager | null = null;
  private segmentMapService: SegmentMapService;

  constructor(
    private readonly config: AppConfig,
    private readonly requestTimeoutMs = 10000
  ) {
    this.segmentMapService = new SegmentMapService(this.config);
  }

  setWebSocketManager(manager: WebSocketManager) {
    this.webSocketManager = manager;
  }

  async handleTrackChange(roomId: string, state: PlayerTelemetryState) {
    logger.info({ roomId, track: state.song }, 'Track changed, fetching lyrics');

    const request: LyricsRequest = {
      song: state.song,
      artist: state.artist,
      videoId: state.trackId,
      durationMs: state.duration,
      album: '',
    };

    try {
      // Check for segment map if it's a music video
      const segmentMap = await this.segmentMapService.getSegmentMap(state.trackId);
      if (segmentMap && segmentMap.counterpartVideoId) {
        request.videoId = segmentMap.counterpartVideoId;
        // TODO: Store segmentMap for later adjustment
      }

      const lyrics = await this.getLyrics(request, segmentMap);

      if (this.webSocketManager) {
        this.webSocketManager.broadcastEvent(roomId, 'lyrics:update', lyrics);
      }
    } catch (error) {
      logger.error({ error, roomId }, 'Failed to fetch lyrics');
    }
  }

  async getLyrics(request: LyricsRequest, segmentMap: SegmentMap | null = null): Promise<LyricsResponse> {
    if (!request.song?.trim() || !request.artist?.trim()) {
      throw new Error('Song and artist are required for lyrics lookup');
    }

    const providerRequest: LyricsRequest = { ...request };

    // Cache removed as per Better Lyrics Parity Plan
    // const cacheKey = `lyrics:${request.videoId}`;
    // const cached = this.cache.get(cacheKey);
    // if (cached && cached.expiresAt > Date.now() && cached.version === CACHE_VERSION) {
    //   return cached.payload;
    // }

    const sharedState: ProviderSharedState = {
      blyrics: new Map(),
      lrclib: new Map(),
    };

    if (segmentMap?.counterpartVideoId) {
      providerRequest.videoId = segmentMap.counterpartVideoId;
    }

    let selected: LyricSourceResult | null = null;

    for (const provider of PROVIDER_PRIORITY) {
      const result = await this.runProvider(provider, providerRequest, sharedState);
      if (result && result.lyrics && result.lyrics.length > 0) {
        // Validation: Check against YouTube official lyrics if available
        if (providerRequest.youtubeLyricsText) {
          const providerText = result.lyrics.map(l => l.words).join(' ');
          const similarity = calculateSimilarity(providerText, providerRequest.youtubeLyricsText);
          if (similarity < 0.5) {
            logger.warn({ provider, similarity, song: providerRequest.song }, 'Lyrics rejected due to low similarity with official lyrics');
            continue;
          }
        }
        selected = result;
        break;
      }
    }

    if (!selected) {
      selected = {
        lyrics: [
          {
            startTimeMs: 0,
            words: NO_LYRICS_TEXT,
            durationMs: 0,
          },
        ],
        source: 'Better Lyrics',
        sourceHref: '',
        musicVideoSynced: false,
        cacheAllowed: false,
      };
    }

    if (selected && segmentMap && !selected.musicVideoSynced) {
      selected.lyrics = this.applySegmentMap(selected.lyrics, segmentMap);
      selected.musicVideoSynced = true;
    }

    const response: LyricsResponse = {
      ...selected,
      song: providerRequest.song,
      artist: providerRequest.artist,
      album: providerRequest.album,
      durationMs: providerRequest.durationMs,
      videoId: providerRequest.videoId,
      segmentMap,
    };

    // Cache set removed
    // if (response.cacheAllowed !== false) {
    //   this.cache.set(cacheKey, {
    //     version: CACHE_VERSION,
    //     expiresAt: Date.now() + CACHE_TTL_MS,
    //     payload: response,
    //   });
    // }

    return response;
  }

  private async runProvider(
    key: ProviderKey,
    request: LyricsRequest,
    sharedState: ProviderSharedState,
  ): Promise<LyricSourceResult | null> {
    const signal = AbortSignal.timeout(this.requestTimeoutMs);

    switch (key) {
      case 'bLyrics-richsynced':
        return loadBlyricsRichSync(request, signal, sharedState.blyrics);
      case 'bLyrics-synced':
        return loadBlyricsLineSync(request, signal, sharedState.blyrics);
      case 'lrclib-synced':
        return loadLrclibSynced(request, signal, sharedState.lrclib);
      case 'lrclib-plain':
        return loadLrclibPlain(request, signal, sharedState.lrclib);
      case 'yt-captions':
        return loadYoutubeCaptions(request, signal);
      case 'yt-lyrics':
        return loadYoutubeLyrics(request);
      default:
        return null;
    }
  }

  private applySegmentMap(lyrics: LyricLine[] | null, segmentMap: SegmentMap): LyricLine[] | null {
    if (!lyrics) return null;

    const offset = segmentMap.primaryVideoStartTimeMilliseconds - segmentMap.counterpartVideoStartTimeMilliseconds;

    return lyrics.map((line) => {
      const newLine = { ...line };
      newLine.startTimeMs += offset;

      if (newLine.parts) {
        newLine.parts = newLine.parts.map((part) => ({
          ...part,
          startTimeMs: part.startTimeMs + offset,
        }));
      }

      if (newLine.timedRomanization) {
        newLine.timedRomanization = newLine.timedRomanization.map((part) => ({
          ...part,
          startTimeMs: part.startTimeMs + offset,
        }));
      }

      return newLine;
    });
  }
}
