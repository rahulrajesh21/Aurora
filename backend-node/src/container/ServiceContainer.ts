import { loadConfig, AppConfig, RoomStorageDriver } from '../config/appConfig';
import { YouTubeMusicProvider } from '../adapters/YouTubeMusicProvider';
import { ProviderType } from '../models/ProviderType';
import { QueueManager } from '../services/QueueManager';
import { FileQueueStorage } from '../storage/FileQueueStorage';
import { PlaybackEngine } from '../services/PlaybackEngine';
import { StreamingService } from '../services/StreamingService';
import { StateManager } from '../services/StateManager';
import { WebSocketManager } from '../services/WebSocketManager';
import { MusicProvider } from '../adapters/MusicProvider';
import { FileRoomStorage } from '../storage/FileRoomStorage';
import { LibSQLRoomStorage } from '../storage/LibSQLRoomStorage';
import { RoomStorage } from '../storage/RoomStorage';
import { RoomManager } from '../services/RoomManager';

export class ServiceContainer {
  private static instance: ServiceContainer | null = null;

  static async initialize(): Promise<ServiceContainer> {
    if (!ServiceContainer.instance) {
      const container = new ServiceContainer();
      await container.bootstrap();
      ServiceContainer.instance = container;
    }
    return ServiceContainer.instance;
  }

  static getInstance(): ServiceContainer {
    if (!ServiceContainer.instance) {
      throw new Error('ServiceContainer not initialized');
    }
    return ServiceContainer.instance;
  }

  private readonly config: AppConfig;
  private readonly providers: Map<ProviderType, MusicProvider>;
  private readonly webSocketManager: WebSocketManager;
  private readonly streamingService: StreamingService;
  private readonly roomManager: RoomManager;

  private constructor() {
    this.config = loadConfig();
    const roomStorage = this.createRoomStorage();
    this.providers = new Map([
      [ProviderType.YOUTUBE, new YouTubeMusicProvider(this.config.youtube.apiKey)],
    ]);
    this.webSocketManager = new WebSocketManager();
    this.roomManager = RoomManager.create(this.config, roomStorage);
    this.streamingService = new StreamingService(
      this.providers,
      this.webSocketManager,
      this.config,
    );
  }

  private createRoomStorage(): RoomStorage {
    if (this.config.rooms.storageDriver === RoomStorageDriver.LIBSQL) {
      const options = this.config.rooms.libsql;
      if (!options) {
        throw new Error('LibSQL storage selected but no connection details were provided');
      }
      return LibSQLRoomStorage.fromOptions(options);
    }
    return new FileRoomStorage(this.config.rooms.storagePath);
  }

  private async bootstrap(): Promise<void> {
    await this.roomManager.init();
  }

  getConfig(): AppConfig {
    return this.config;
  }

  getStreamingService(): StreamingService {
    return this.streamingService;
  }

  getWebSocketManager(): WebSocketManager {
    return this.webSocketManager;
  }

  getRoomManager(): RoomManager {
    return this.roomManager;
  }
}
