import { loadConfig, AppConfig, RoomStorageDriver } from '../config/appConfig';
import { YouTubeMusicScraperProvider } from '../adapters/YouTubeMusicScraperProvider';
import { YouTubeMusicProvider } from '../adapters/YouTubeMusicProvider';
import { ProviderType } from '../models/ProviderType';
import { QueueManager } from '../services/QueueManager';
import { FileQueueStorage } from '../storage/FileQueueStorage';
import { PlaybackEngine } from '../services/PlaybackEngine';
import { StreamingService } from '../services/StreamingService';
import { StateManager } from '../services/StateManager';
import { WebSocketManager } from '../services/WebSocketManager';
import { MusicProvider } from '../adapters/MusicProvider';
import { LibSQLRoomStorage } from '../storage/LibSQLRoomStorage';
import { RoomStorage } from '../storage/RoomStorage';
import { RoomManager } from '../services/RoomManager';
import { LyricsService } from '../services/lyrics/LyricsService';
import { PopularAlbumsService } from '../services/PopularAlbumsService';
import { ItunesService } from '../services/ItunesService';
import { PotProviderService } from '../services/PotProviderService';

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
  private readonly lyricsService: LyricsService;
  private readonly popularAlbumsService: PopularAlbumsService;
  private readonly itunesService: ItunesService;
  private readonly potProviderService: PotProviderService;

  private constructor() {
    this.config = loadConfig();
    const roomStorage = this.createRoomStorage();
    this.providers = new Map([
      [ProviderType.YOUTUBE, new YouTubeMusicScraperProvider(this.config.youtube.apiKey, this.config.youtube)],
    ]);
    this.webSocketManager = new WebSocketManager();
    this.roomManager = RoomManager.create(this.config, roomStorage);

    this.itunesService = new ItunesService();
    this.lyricsService = new LyricsService();

    this.popularAlbumsService = new PopularAlbumsService(this.config.popularAlbums);
    this.potProviderService = new PotProviderService(this.config.youtube.ytdlp);

    this.webSocketManager.setLyricsService(this.lyricsService);
    this.lyricsService.setWebSocketManager(this.webSocketManager);
    this.streamingService = new StreamingService(
      this.providers,
      this.webSocketManager,
      this.config,
      this.roomManager,
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
    throw new Error(`Unsupported storage driver: ${this.config.rooms.storageDriver}. Only LibSQL is supported.`);
  }

  private async bootstrap(): Promise<void> {
    await Promise.all([
      this.roomManager.init(),
      this.popularAlbumsService.init(),
      this.potProviderService.start(),
    ]);
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

  getLyricsService(): LyricsService {
    return this.lyricsService;
  }

  getPopularAlbumsService(): PopularAlbumsService {
    return this.popularAlbumsService;
  }

  getPotProviderService(): PotProviderService {
    return this.potProviderService;
  }
}
