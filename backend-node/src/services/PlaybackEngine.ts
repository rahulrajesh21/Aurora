import { MusicProvider } from '../adapters/MusicProvider';
import { ProviderType } from '../models/ProviderType';
import { PlaybackState } from '../models/PlaybackState';
import { AudioFormat, StreamInfo } from '../models/StreamInfo';
import {
  InvalidSeekPosition,
  NetworkError,
  ProviderError,
  QueueError,
  StreamingError,
} from '../models/StreamingError';
import { Track } from '../models/Track';
import { QueueManager } from './QueueManager';

export class PlaybackEngine {
  private currentTrack: Track | null = null;
  private currentProvider: MusicProvider | null = null;
  private currentStreamInfo: StreamInfo | null = null;
  private positionSeconds = 0;
  private isPlaying = false;
  private lastUpdateTimestamp = Date.now();

  private getLivePosition(): number {
    const track = this.currentTrack;
    if (!track) {
      return 0;
    }
    if (!this.isPlaying) {
      return Math.min(this.positionSeconds, track.durationSeconds);
    }
    const elapsedMs = Date.now() - this.lastUpdateTimestamp;
    if (elapsedMs <= 0) {
      return Math.min(this.positionSeconds, track.durationSeconds);
    }
    const elapsedSeconds = elapsedMs / 1000;
    const updatedPosition = this.positionSeconds + elapsedSeconds;
    return Math.min(updatedPosition, track.durationSeconds);
  }

  private snapshotPosition(): number {
    const track = this.currentTrack;
    if (!track) {
      this.positionSeconds = 0;
      this.lastUpdateTimestamp = Date.now();
      return 0;
    }
    const livePosition = this.getLivePosition();
    this.positionSeconds = livePosition;
    this.lastUpdateTimestamp = Date.now();
    return livePosition;
  }

  constructor(
    private readonly providers: Map<ProviderType, MusicProvider>,
    private readonly queueManager: QueueManager,
    private readonly maxRetryAttempts = 3,
    private readonly initialRetryDelayMs = 1000,
  ) {}

  async startPlayback(track: Track): Promise<StreamInfo> {
    const provider = this.providers.get(track.provider);
    if (!provider) {
      throw new ProviderError(track.provider, 'Provider not available');
    }
    const streamInfo = await provider.getStreamUrl(track.id);
    this.currentTrack = track;
    this.currentProvider = provider;
    this.currentStreamInfo = streamInfo;
    this.positionSeconds = 0;
    this.isPlaying = true;
    this.lastUpdateTimestamp = Date.now();
    this.queueManager.setCurrentlyPlaying(track.id);
    return streamInfo;
  }

  async stopPlayback(): Promise<void> {
    this.currentTrack = null;
    this.currentProvider = null;
    this.currentStreamInfo = null;
    this.positionSeconds = 0;
    this.isPlaying = false;
    this.lastUpdateTimestamp = Date.now();
  }

  async pause(): Promise<void> {
    this.snapshotPosition();
    this.isPlaying = false;
  }

  async resume(): Promise<void> {
    if (!this.currentTrack) {
      throw new NetworkError('No track to resume');
    }
    this.isPlaying = true;
    this.lastUpdateTimestamp = Date.now();
  }

  updatePosition(position: number): void {
    if (!this.currentTrack) return;
    if (position < 0 || position > this.currentTrack.durationSeconds) {
      return;
    }
    this.positionSeconds = position;
    this.lastUpdateTimestamp = Date.now();
  }

  async seekTo(positionSeconds: number): Promise<void> {
    const track = this.currentTrack;
    if (!track) {
      throw new NetworkError('No track currently playing');
    }
    if (positionSeconds < 0 || positionSeconds > track.durationSeconds) {
      throw new InvalidSeekPosition(positionSeconds, track.durationSeconds);
    }
    this.positionSeconds = positionSeconds;
    this.lastUpdateTimestamp = Date.now();
  }

  async playNext(): Promise<StreamInfo> {
    const nextItem = await this.queueManager.popNextTrack();
    if (!nextItem) {
      throw new QueueError('Queue is empty, no next track available');
    }
    const result = await this.startPlayback(nextItem.track);
    return result;
  }

  async reconnectStream(): Promise<StreamInfo> {
    const track = this.currentTrack;
    if (!track) {
      throw new NetworkError('No track to reconnect');
    }

    const savedPosition = this.positionSeconds;
    const wasPlaying = this.isPlaying;
    let lastError: StreamingError | null = null;

    for (let attempt = 0; attempt < this.maxRetryAttempts; attempt += 1) {
      try {
        const provider = this.providers.get(track.provider);
        if (!provider) {
          throw new ProviderError(track.provider, 'Provider not available');
        }
        const streamInfo = await provider.getStreamUrl(track.id);
        this.currentStreamInfo = streamInfo;
        this.currentProvider = provider;
        this.positionSeconds = savedPosition;
        this.isPlaying = wasPlaying;
        this.lastUpdateTimestamp = Date.now();
        return streamInfo;
      } catch (error) {
        if (error instanceof StreamingError) {
          lastError = error;
        } else {
          lastError = new NetworkError(`Reconnection attempt failed: ${(error as Error).message}`, error);
        }
        if (attempt < this.maxRetryAttempts - 1) {
          const delayMs = this.initialRetryDelayMs * 2 ** attempt;
          await new Promise((resolve) => setTimeout(resolve, delayMs));
        }
      }
    }

    this.isPlaying = false;
    this.lastUpdateTimestamp = Date.now();
    throw lastError ?? new NetworkError('Failed to reconnect after retries');
  }

  async startPlaybackWithRetry(track: Track): Promise<StreamInfo> {
    try {
      return await this.startPlayback(track);
    } catch (error) {
      this.currentTrack = track;
      return this.reconnectStream();
    }
  }

  getCurrentState(): PlaybackState {
    const positionSeconds = this.snapshotPosition();
    return {
      currentTrack: this.currentTrack,
      positionSeconds,
      isPlaying: this.isPlaying,
      queue: this.queueManager.getQueueSnapshot(),
      shuffleEnabled: this.queueManager.isShuffleEnabled(),
      timestamp: Date.now(),
      streamUrl: this.currentStreamInfo?.streamUrl ?? null,
      streamFormat: this.currentStreamInfo?.format ?? AudioFormat.WEBM,
    };
  }
}
