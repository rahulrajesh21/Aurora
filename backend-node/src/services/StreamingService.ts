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
import { QueueManager } from './QueueManager';
import { PlaybackEngine } from './PlaybackEngine';
import { StateManager } from './StateManager';
import { WebSocketManager } from './WebSocketManager';
import { logger } from '../utils/logger';
import { SearchResult } from '../models/SearchResult';

export class StreamingService {
  constructor(
    private readonly providers: Map<ProviderType, MusicProvider>,
    private readonly queueManager: QueueManager,
    private readonly playbackEngine: PlaybackEngine,
    private readonly stateManager: StateManager,
    private readonly webSocketManager: WebSocketManager,
  ) {}

  private async updateAndBroadcast(state: PlaybackState): Promise<void> {
    await this.stateManager.updateState(state);
    await this.stateManager.persistState();
    await this.webSocketManager.broadcastState(state);
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

  async play(trackId: string, providerType: ProviderType): Promise<PlaybackState> {
    logger.info({ trackId, providerType }, 'Starting playback');
    const provider = this.providers.get(providerType);
    if (!provider) {
      throw new ProviderError(providerType, 'Provider not available');
    }
    const track = await provider.getTrack(trackId);
    if (!track) {
      throw new TrackNotFoundError(trackId, providerType);
    }
    await this.playbackEngine.startPlayback(track);
    const newState = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(newState);
    return newState;
  }

  async pause(): Promise<PlaybackState> {
    await this.playbackEngine.pause();
    const newState = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(newState);
    return newState;
  }

  async resume(): Promise<PlaybackState> {
    try {
      await this.playbackEngine.resume();
    } catch (error) {
      if (error instanceof NetworkError && error.message.includes('No track to resume')) {
        const nextItem = await this.queueManager.popNextTrack();
        if (!nextItem) {
          throw new QueueError('No track available in queue to start playback');
        }
        await this.playbackEngine.startPlayback(nextItem.track);
      } else {
        throw error;
      }
    }
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
    return state;
  }

  async skip(): Promise<PlaybackState> {
    await this.playbackEngine.playNext();
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
    return state;
  }

  async seek(positionSeconds: number): Promise<PlaybackState> {
    await this.playbackEngine.seekTo(positionSeconds);
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
    return state;
  }

  async seekByPercentage(percentage: number): Promise<PlaybackState> {
    if (percentage < 0 || percentage > 100) {
      throw new NetworkError('Percentage must be between 0 and 100');
    }
    const currentState = this.playbackEngine.getCurrentState();
    if (!currentState.currentTrack) {
      throw new NetworkError('No track currently playing');
    }
    const positionSeconds = Math.floor((percentage / 100) * currentState.currentTrack.durationSeconds);
    return this.seek(positionSeconds);
  }

  async getState(): Promise<PlaybackState> {
    return this.stateManager.getState();
  }

  async addToQueue(trackId: string, providerType: ProviderType): Promise<void> {
    const provider = this.providers.get(providerType);
    if (!provider) {
      throw new ProviderError(providerType, 'Provider not available');
    }
    const track = await provider.getTrack(trackId);
    if (!track) {
      throw new TrackNotFoundError(trackId, providerType);
    }
    await this.queueManager.addTrack(track, 'user');
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
  }

  async removeFromQueue(position: number): Promise<void> {
    await this.queueManager.removeTrack(position);
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
  }

  async reorderQueue(from: number, to: number): Promise<void> {
    await this.queueManager.reorderTrack(from, to);
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
  }

  async clearQueue(): Promise<void> {
    await this.queueManager.clearQueue();
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
  }

  async shuffleQueue(): Promise<void> {
    await this.queueManager.shuffle();
    const state = this.playbackEngine.getCurrentState();
    await this.updateAndBroadcast(state);
  }

  getQueue(): Track[] {
    return this.queueManager.getQueueSnapshot();
  }
}
