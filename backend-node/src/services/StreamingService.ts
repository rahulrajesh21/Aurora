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
import { GemmaMetadataService } from './lyrics/GemmaMetadataService';

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
    await this.updateAndBroadcast(roomId, session, newState);
    return newState;
  }

  async pause(roomId: string, positionSeconds?: number): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    if (typeof positionSeconds === 'number') {
      session.playbackEngine.updatePosition(positionSeconds);
    }
    await session.playbackEngine.pause();
    const newState = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, newState);
    return newState;
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
    await this.updateAndBroadcast(roomId, session, state);
    return state;
  }

  async skip(roomId: string): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    await session.playbackEngine.playNext();
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
    return state;
  }

  async seek(roomId: string, positionSeconds: number): Promise<PlaybackState> {
    const session = await this.getSession(roomId);
    await session.playbackEngine.seekTo(positionSeconds);
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
    return state;
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
    return session.stateManager.getState();
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

    // Normalize metadata using Gemma
    try {
      // We instantiate it here or inject it. Since it was used directly in LyricsService, we'll do the same for now.
      // Ideally this should be injected.
      const metadataService = new GemmaMetadataService();
      const normalized = await metadataService.normalizeMetadata({
        song: track.title,
        artist: track.artist,
      });

      if (normalized) {
        logger.info({ original: track.title, normalized }, 'Normalized track metadata');
        track.title = normalized.song;
        track.artist = normalized.artist;
      }
    } catch (error) {
      logger.warn({ error }, 'Failed to normalize metadata, using original');
    }

    await session.queueManager.addTrack(track, 'user');
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
  }

  async removeFromQueue(roomId: string, position: number): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.removeTrack(position);
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
  }

  async reorderQueue(roomId: string, from: number, to: number): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.reorderTrack(from, to);
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
  }

  async clearQueue(roomId: string): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.clearQueue();
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
  }

  async shuffleQueue(roomId: string): Promise<void> {
    const session = await this.getSession(roomId);
    await session.queueManager.shuffle();
    const state = session.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(roomId, session, state);
  }

  async getQueue(roomId: string): Promise<Track[]> {
    const session = await this.getSession(roomId);
    return session.queueManager.getQueueSnapshot();
  }
}
