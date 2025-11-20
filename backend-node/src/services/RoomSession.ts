import path from 'node:path';
import { AppConfig } from '../config/appConfig';
import { MusicProvider } from '../adapters/MusicProvider';
import { ProviderType } from '../models/ProviderType';
import { QueueManager } from './QueueManager';
import { PlaybackEngine } from './PlaybackEngine';
import { StateManager } from './StateManager';
import { FileQueueStorage } from '../storage/FileQueueStorage';

export class RoomSession {
  public readonly queueManager: QueueManager;
  public readonly playbackEngine: PlaybackEngine;
  public readonly stateManager: StateManager;

  constructor(
    public readonly roomId: string,
    private readonly config: AppConfig,
    private readonly providers: Map<ProviderType, MusicProvider>
  ) {
    const roomDataDir = path.join(process.cwd(), 'data', 'rooms', roomId);
    
    // Initialize Queue
    const queueStoragePath = path.join(roomDataDir, 'queue.json');
    const queueStorage = config.queue.persistenceEnabled
      ? new FileQueueStorage(queueStoragePath)
      : undefined;
    this.queueManager = new QueueManager(queueStorage, config.queue.maxSize);

    // Initialize State
    const stateStoragePath = path.join(roomDataDir, 'state.json');
    this.stateManager = new StateManager(stateStoragePath, config.state.persistenceEnabled);

    // Initialize Playback Engine
    this.playbackEngine = new PlaybackEngine(
      providers,
      this.queueManager,
      config.playback.retryAttempts,
      config.playback.retryBackoffMs[0] ?? 1000
    );
  }

  async init(): Promise<void> {
    await this.queueManager.init();
    await this.stateManager.restoreState();
  }
}
