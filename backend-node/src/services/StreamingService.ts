import { MusicProvider } from '../adapters/MusicProvider';
import { ProviderType } from '../models/ProviderType';
import { PlaybackState } from '../models/PlaybackState';
import { Track } from '../models/Track';
import {
  NetworkError,
  ProviderError,
  QueueError,
  StreamingError,
  TrackNotFoundError,
} from '../models/StreamingError';
import { WebSocketManager } from './WebSocketManager';
import { logger } from '../utils/logger';
import { SearchResult } from '../models/SearchResult';
import { AppConfig } from '../config/appConfig';
import { RoomSession } from './RoomSession';
import { RoomManager } from './RoomManager';



const STREAM_CACHE_TTL_MS = 5 * 60 * 60 * 1000; // 5 hours (YouTube URLs valid for 6 hours)
const streamUrlCache = new Map<string, { url: string; expiresAt: number }>();

export class StreamingService {
  private readonly sessions = new Map<string, RoomSession>();

  constructor(
    private readonly providers: Map<ProviderType, MusicProvider>,
    private readonly webSocketManager: WebSocketManager,
    private readonly config: AppConfig,
    private readonly roomManager: RoomManager,
  ) { }

  private async getSession(roomId: string): Promise<RoomSession> {
    let session = this.sessions.get(roomId);
    if (!session) {
      session = new RoomSession(roomId, this.config, this.providers);
      await session.init();
      this.sessions.set(roomId, session);
      const restoredState = await session.stateManager.getState();
      await this.roomManager.updatePlaybackState(roomId, restoredState);
    }
    return session;
  }

  private async updateAndBroadcast(roomId: string, session: RoomSession, state: PlaybackState): Promise<void> {
    await session.stateManager.updateState(state);
    await session.stateManager.persistState();
    await this.roomManager.updatePlaybackState(roomId, state);
    await this.webSocketManager.broadcastState(roomId, state);

    // Trigger prefetch for the next track
    this.prefetchNextTrack(roomId).catch(err => {
      logger.warn({ err, roomId }, 'Failed to prefetch next track');
    });
  }

  private async prefetchNextTrack(roomId: string): Promise<void> {
    const session = await this.getSession(roomId);
    const queue = session.queueManager.getQueueSnapshot();
    if (queue.length === 0) return;

    // The next track is the first one in the queue (since current track is popped)
    // OR if we are looking at the queue structure where current is separate, we need the first in queue.
    // Based on PlaybackEngine, popNextTrack takes from queue. So queue[0] is next.
    const nextTrack = queue[0];
    if (nextTrack) {
      logger.info({ trackId: nextTrack.id }, 'Prefetching next track stream URL');
      await this.resolveStreamUrl(nextTrack.id);
    }
  }

  private enrichState(state: PlaybackState): PlaybackState {
    if (state.streamUrl && state.currentTrack) {
      // Replace raw stream URL with proxy URL
      // The frontend will prepend the base URL
      return {
        ...state,
        streamUrl: `/api/playback/stream/${state.currentTrack.id}`,
      };
    }
    return state;
  }

  async play(roomId: string, trackId: string, providerType: ProviderType): Promise<PlaybackState> {
    logger.info({ roomId, trackId, providerType }, 'Starting playback');
    const session = await this.getSession(roomId);

    const provider = this.providers.get(providerType);
    if (!provider) {
      throw new ProviderError(providerType, 'Provider not available');
    }
    const track = await provider.getTrack(trackId);
    if (!track) {
      throw new TrackNotFoundError(trackId, providerType);
    }

    await session.playbackEngine.startPlayback(track);
    const newState = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(newState);
    await this.updateAndBroadcast(roomId, session, enrichedState);
    return enrichedState;
  }

  async pause(roomId: string, positionSeconds?: number): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    if (typeof positionSeconds === 'number') {
      session.playbackEngine.updatePosition(positionSeconds);
    }
    await session.playbackEngine.pause();
    const newState = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(newState);
    await this.updateAndBroadcast(roomId, session, enrichedState);
    return enrichedState;
  }

  async resume(roomId: string): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    try {
      await session.playbackEngine.resume();
    } catch (error) {
      if (error instanceof NetworkError && error.message.includes('No track to resume')) {
        const nextItem = await session.queueManager.popNextTrack();
        if (!nextItem) {
          throw new QueueError('No track available in queue to start playback');
        }
        await session.playbackEngine.startPlayback(nextItem.track);
      } else {
        throw error;
      }
    }
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
    return enrichedState;
  }

  async skip(roomId: string): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    await session.playbackEngine.playNext();
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
    return enrichedState;
  }

  async seek(roomId: string, positionSeconds: number): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    await session.playbackEngine.seekTo(positionSeconds);
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
    return enrichedState;
  }

  async seekByPercentage(roomId: string, percentage: number): Promise<PlaybackState> {
    if (percentage < 0 || percentage > 100) {
      throw new NetworkError('Percentage must be between 0 and 100');
    }
    const session = await this.getSession(roomId);
    const currentState = session.playbackEngine.getCurrentState();
    if (!currentState.currentTrack) {
      throw new NetworkError('No track currently playing');
    }
    const positionSeconds = Math.floor((percentage / 100) * currentState.currentTrack.durationSeconds);
    return this.seek(roomId, positionSeconds);
  }

  async getState(roomId: string): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    const state = await session.stateManager.getState();
    return this.enrichState(state);
  }

  async resolveStreamUrl(trackId: string): Promise<string> {
    const cached = streamUrlCache.get(trackId);
    if (cached && cached.expiresAt > Date.now()) {
      return cached.url;
    }
    const streamUrl = await this.fetchStreamUrl(trackId);
    streamUrlCache.set(trackId, { url: streamUrl, expiresAt: Date.now() + STREAM_CACHE_TTL_MS });
    return streamUrl;
  }

  private async fetchStreamUrl(trackId: string): Promise<string> {
    const provider = this.providers.get(ProviderType.YOUTUBE);
    if (!provider) {
      throw new ProviderError(ProviderType.YOUTUBE, 'YouTube provider not available');
    }

    try {
      const streamInfo = await provider.getStreamUrl(trackId);
      return streamInfo.streamUrl;
    } catch (error) {
      logger.error({ error, trackId }, 'Failed to resolve stream URL via provider');
      throw error;
    }
  }

  async search(query: string): Promise<SearchResult> {
    if (!query.trim()) {
      throw new NetworkError('Search query cannot be blank');
    }

    const allTracks: Track[] = [];
    const providersUsed: ProviderType[] = [];

    for (const [providerType, provider] of this.providers.entries()) {
      try {
        const tracks = await provider.search(query, 20);
        allTracks.push(...tracks);
        providersUsed.push(providerType);
      } catch (error) {
        logger.warn({ providerType, error }, 'Search failed for provider');
      }
    }

    const sanitized = allTracks.filter((track) => {
      const isValid = Boolean(track?.id && track?.title && track?.artist);
      if (!isValid) {
        logger.warn({ track }, 'Dropping invalid track from search results');
      }
      return isValid;
    });

    return { tracks: sanitized, query, providers: providersUsed };
  }

  async addToQueue(roomId: string, trackId: string, providerType: ProviderType): Promise<void> {
    const session = await this.getSession(roomId);
    const provider = this.providers.get(providerType);
    if (!provider) {
      throw new ProviderError(providerType, 'Provider not available');
    }
    const track = await provider.getTrack(trackId);
    if (!track) {
      throw new TrackNotFoundError(trackId, providerType);
    }

    await session.queueManager.addTrack(track, 'user');
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);

    // Only prefetch if this is the next song in queue (queue has exactly 1 song)
    if (enrichedState.queue.length === 1) {
      logger.info({ trackId: track.id }, 'Prefetching stream URL for next track in queue');
      this.resolveStreamUrl(track.id).catch(err => {
        logger.warn({ err, trackId: track.id }, 'Failed to prefetch stream URL for next track');
      });
    }
  }

  async removeFromQueue(roomId: string, position: number): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.removeTrack(position);
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
  }

  async reorderQueue(roomId: string, from: number, to: number): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.reorderTrack(from, to);
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
  }

  async clearQueue(roomId: string): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.clearQueue();
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
  }

  async shuffleQueue(roomId: string): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.shuffle();
    const state = session.playbackEngine.getCurrentState();
    const enrichedState = this.enrichState(state);
    await this.updateAndBroadcast(roomId, session, enrichedState);
  }

  async getQueue(roomId: string): Promise<Track[]> {
    const session = await this.getSession(roomId);
    return session.queueManager.getQueueSnapshot();
  }
}
