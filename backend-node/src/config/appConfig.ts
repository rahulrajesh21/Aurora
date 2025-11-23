import fs from 'node:fs';
import path from 'node:path';

export class ConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigurationError';
  }
}

export interface YouTubeConfig {
  apiKey: string;
  searchTimeout: number;
  searchLimit: number;
  ytdlp?: {
    cookiesPath?: string;
    poToken?: string;
    visitorData?: string;
    userAgent?: string;
    potProvider?: {
      enabled: boolean;
      port: number;
    };
  };
}

export interface QueueConfig {
  maxSize: number;
  persistenceEnabled: boolean;
  storagePath: string;
}

export interface PlaybackConfig {
  seekTimeoutMs: number;
  retryAttempts: number;
  retryBackoffMs: number[];
  startPlaybackTimeoutMs: number;
  transitionTimeoutMs: number;
}

export interface StateConfig {
  persistenceEnabled: boolean;
  storagePath: string;
}

export interface WebSocketConfig {
  broadcastDelayMs: number;
}

export interface PopularAlbumsConfig {
  cachePath: string;
  refreshIntervalHours: number;
  lastFmBaseUrl: string;
  lastFmApiKey: string;
  lastFmMethod: string;
  lastFmFormat: string;
  requestLimit: number;
  maxAlbums: number;
  fallbackDataPath: string;
}

export interface RoomInviteConfig {
  ttlSeconds: number;
  maxPending: number;
}

export enum RoomStorageDriver {
  FILE = 'file',
  LIBSQL = 'libsql',
}

export interface RoomLibSQLConfig {
  url: string;
  authToken?: string;
}

export interface RoomConfig {
  storageDriver: RoomStorageDriver;
  storagePath: string;
  maxMembers: number;
  idleTimeoutMs: number;
  invite: RoomInviteConfig;
  libsql?: RoomLibSQLConfig;
}

export interface AppConfig {
  youtube: YouTubeConfig;
  queue: QueueConfig;
  playback: PlaybackConfig;
  state: StateConfig;
  websocket: WebSocketConfig;
  rooms: RoomConfig;
  popularAlbums: PopularAlbumsConfig;
}

interface RawConfigFile {
  streaming?: {
    youtube?: Partial<YouTubeConfig>;
    queue?: Partial<QueueConfig>;
    playback?: Partial<PlaybackConfig>;
    state?: Partial<StateConfig>;
    websocket?: Partial<WebSocketConfig>;
    rooms?: Partial<Omit<RoomConfig, 'invite' | 'libsql'>> & {
      invite?: Partial<RoomInviteConfig>;
      libsql?: Partial<RoomLibSQLConfig>;
    };
    popularAlbums?: Partial<PopularAlbumsConfig>;
  };
}

const DEFAULTS: AppConfig = {
  youtube: {
    apiKey: '',
    searchTimeout: 2000,
    searchLimit: 20,
    ytdlp: {
      cookiesPath: './cookies.txt',
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      potProvider: {
        enabled: true,
        port: 4416,
      },
    },
  },
  queue: {
    maxSize: 100,
    persistenceEnabled: true,
    storagePath: './data/queue.json',
  },
  playback: {
    seekTimeoutMs: 1000,
    retryAttempts: 3,
    retryBackoffMs: [1000, 2000, 4000],
    startPlaybackTimeoutMs: 2000,
    transitionTimeoutMs: 2000,
  },
  state: {
    persistenceEnabled: true,
    storagePath: './data/state.json',
  },
  websocket: {
    broadcastDelayMs: 500,
  },
  popularAlbums: {
    cachePath: './data/popular-albums.json',
    refreshIntervalHours: 24,
    lastFmBaseUrl: 'https://ws.audioscrobbler.com/2.0/',
    lastFmApiKey: '',
    lastFmMethod: 'chart.gettopalbums',
    lastFmFormat: 'json',
    requestLimit: 100,
    maxAlbums: 50,
    fallbackDataPath: './data/popular-albums.fallback.json',
  },
  rooms: {
    storageDriver: RoomStorageDriver.FILE,
    storagePath: './data/rooms',
    maxMembers: 25,
    idleTimeoutMs: 15 * 60 * 1000,
    invite: {
      ttlSeconds: 24 * 60 * 60,
      maxPending: 10,
    },
    libsql: {
      url: '',
      authToken: undefined,
    },
  },
};

export function loadConfig(): AppConfig {
  const configPath = path.resolve(process.cwd(), 'config/app.config.json');
  let fileOverrides: RawConfigFile = {};

  if (fs.existsSync(configPath)) {
    try {
      const contents = fs.readFileSync(configPath, 'utf-8');
      fileOverrides = JSON.parse(contents) as RawConfigFile;
    } catch (error) {
      throw new ConfigurationError(`Unable to parse config file: ${configPath}`);
    }
  }

  const streaming = fileOverrides.streaming ?? {};

  const youtubeApiKey = process.env.YOUTUBE_API_KEY ?? streaming.youtube?.apiKey ?? DEFAULTS.youtube.apiKey;

  if (!youtubeApiKey) {
    throw new ConfigurationError('YouTube API key is required. Set YOUTUBE_API_KEY env or update config/app.config.json');
  }

  const roomOverrides = streaming.rooms ?? {};
  const requestedDriver = process.env.ROOMS_STORAGE_DRIVER ?? roomOverrides.storageDriver ?? DEFAULTS.rooms.storageDriver;
  const storageDriver = normalizeRoomStorageDriver(requestedDriver);

  const libsqlUrl = String(
    process.env.ROOMS_LIBSQL_URL ??
    process.env.TURSO_DATABASE_URL ??
    roomOverrides.libsql?.url ??
    DEFAULTS.rooms.libsql?.url ??
    '',
  ).trim();
  const libsqlAuthToken =
    process.env.ROOMS_LIBSQL_AUTH_TOKEN ??
    process.env.TURSO_AUTH_TOKEN ??
    roomOverrides.libsql?.authToken ??
    DEFAULTS.rooms.libsql?.authToken;

  if (storageDriver === RoomStorageDriver.LIBSQL && !libsqlUrl) {
    throw new ConfigurationError('ROOMS_LIBSQL_URL is required when ROOMS_STORAGE_DRIVER=libsql');
  }

  const config: AppConfig = {
    youtube: {
      apiKey: youtubeApiKey,
      searchTimeout: Number(process.env.YOUTUBE_SEARCH_TIMEOUT ?? streaming.youtube?.searchTimeout ?? DEFAULTS.youtube.searchTimeout),
      searchLimit: Number(process.env.YOUTUBE_SEARCH_LIMIT ?? streaming.youtube?.searchLimit ?? DEFAULTS.youtube.searchLimit),
      ytdlp: {
        cookiesPath: String(process.env.YTDLP_COOKIES_PATH ?? streaming.youtube?.ytdlp?.cookiesPath ?? DEFAULTS.youtube.ytdlp?.cookiesPath ?? './cookies.txt'),
        poToken: process.env.YT_PO_TOKEN ?? streaming.youtube?.ytdlp?.poToken,
        visitorData: process.env.YT_VISITOR_DATA ?? streaming.youtube?.ytdlp?.visitorData,
        userAgent: String(process.env.YTDLP_USER_AGENT ?? streaming.youtube?.ytdlp?.userAgent ?? DEFAULTS.youtube.ytdlp?.userAgent ?? 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'),
        potProvider: {
          enabled: parseBoolean(process.env.YT_POT_ENABLED ?? streaming.youtube?.ytdlp?.potProvider?.enabled ?? DEFAULTS.youtube.ytdlp?.potProvider?.enabled),
          port: Number(process.env.YT_POT_PORT ?? streaming.youtube?.ytdlp?.potProvider?.port ?? DEFAULTS.youtube.ytdlp?.potProvider?.port),
        },
      },
    },
    queue: {
      maxSize: Number(process.env.QUEUE_MAX_SIZE ?? streaming.queue?.maxSize ?? DEFAULTS.queue.maxSize),
      persistenceEnabled: parseBoolean(process.env.QUEUE_PERSISTENCE_ENABLED ?? streaming.queue?.persistenceEnabled ?? DEFAULTS.queue.persistenceEnabled),
      storagePath: String(process.env.QUEUE_STORAGE_PATH ?? streaming.queue?.storagePath ?? DEFAULTS.queue.storagePath),
    },
    playback: {
      seekTimeoutMs: Number(process.env.PLAYBACK_SEEK_TIMEOUT_MS ?? streaming.playback?.seekTimeoutMs ?? DEFAULTS.playback.seekTimeoutMs),
      retryAttempts: Number(process.env.PLAYBACK_RETRY_ATTEMPTS ?? streaming.playback?.retryAttempts ?? DEFAULTS.playback.retryAttempts),
      retryBackoffMs: parseNumberList(process.env.PLAYBACK_RETRY_BACKOFF_MS) ?? streaming.playback?.retryBackoffMs ?? DEFAULTS.playback.retryBackoffMs,
      startPlaybackTimeoutMs: Number(process.env.PLAYBACK_START_TIMEOUT_MS ?? streaming.playback?.startPlaybackTimeoutMs ?? DEFAULTS.playback.startPlaybackTimeoutMs),
      transitionTimeoutMs: Number(process.env.PLAYBACK_TRANSITION_TIMEOUT_MS ?? streaming.playback?.transitionTimeoutMs ?? DEFAULTS.playback.transitionTimeoutMs),
    },
    state: {
      persistenceEnabled: parseBoolean(process.env.STATE_PERSISTENCE_ENABLED ?? streaming.state?.persistenceEnabled ?? DEFAULTS.state.persistenceEnabled),
      storagePath: String(process.env.STATE_STORAGE_PATH ?? streaming.state?.storagePath ?? DEFAULTS.state.storagePath),
    },
    websocket: {
      broadcastDelayMs: Number(process.env.WS_BROADCAST_DELAY_MS ?? streaming.websocket?.broadcastDelayMs ?? DEFAULTS.websocket.broadcastDelayMs),
    },
    popularAlbums: {
      cachePath: String(process.env.POPULAR_ALBUMS_CACHE_PATH ?? streaming.popularAlbums?.cachePath ?? DEFAULTS.popularAlbums.cachePath),
      refreshIntervalHours: Number(
        process.env.POPULAR_ALBUMS_REFRESH_HOURS ??
        streaming.popularAlbums?.refreshIntervalHours ??
        DEFAULTS.popularAlbums.refreshIntervalHours,
      ),
      lastFmBaseUrl: String(
        process.env.LASTFM_BASE_URL ??
        streaming.popularAlbums?.lastFmBaseUrl ??
        DEFAULTS.popularAlbums.lastFmBaseUrl,
      ),
      lastFmApiKey: String(
        process.env.LASTFM_API_KEY ??
        streaming.popularAlbums?.lastFmApiKey ??
        DEFAULTS.popularAlbums.lastFmApiKey,
      ),
      lastFmMethod: String(
        process.env.LASTFM_METHOD ??
        streaming.popularAlbums?.lastFmMethod ??
        DEFAULTS.popularAlbums.lastFmMethod,
      ),
      lastFmFormat: String(
        process.env.LASTFM_FORMAT ??
        streaming.popularAlbums?.lastFmFormat ??
        DEFAULTS.popularAlbums.lastFmFormat,
      ),
      requestLimit: Number(
        process.env.LASTFM_REQUEST_LIMIT ??
        streaming.popularAlbums?.requestLimit ??
        DEFAULTS.popularAlbums.requestLimit,
      ),
      maxAlbums: Number(
        process.env.POPULAR_ALBUMS_MAX_RESULTS ??
        streaming.popularAlbums?.maxAlbums ??
        DEFAULTS.popularAlbums.maxAlbums,
      ),
      fallbackDataPath: String(
        process.env.POPULAR_ALBUMS_FALLBACK_PATH ??
        streaming.popularAlbums?.fallbackDataPath ??
        DEFAULTS.popularAlbums.fallbackDataPath,
      ),
    },
    rooms: {
      storageDriver,
      storagePath: String(process.env.ROOMS_STORAGE_PATH ?? roomOverrides.storagePath ?? DEFAULTS.rooms.storagePath),
      maxMembers: Number(process.env.ROOMS_MAX_MEMBERS ?? roomOverrides.maxMembers ?? DEFAULTS.rooms.maxMembers),
      idleTimeoutMs: Number(process.env.ROOMS_IDLE_TIMEOUT_MS ?? roomOverrides.idleTimeoutMs ?? DEFAULTS.rooms.idleTimeoutMs),
      invite: {
        ttlSeconds: Number(process.env.ROOMS_INVITE_TTL_SECONDS ?? roomOverrides.invite?.ttlSeconds ?? DEFAULTS.rooms.invite.ttlSeconds),
        maxPending: Number(process.env.ROOMS_INVITE_MAX_PENDING ?? roomOverrides.invite?.maxPending ?? DEFAULTS.rooms.invite.maxPending),
      },
      libsql: libsqlUrl
        ? {
          url: libsqlUrl,
          authToken: libsqlAuthToken,
        }
        : undefined,
    },
  };

  if (!config.popularAlbums.lastFmApiKey) {
    throw new ConfigurationError(
      'Last.fm API key is required. Set LASTFM_API_KEY env or update streaming.popularAlbums.lastFmApiKey',
    );
  }

  return config;
}

function parseBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'string') {
    return value.toLowerCase() === 'true';
  }
  return Boolean(value);
}

function parseNumberList(value?: string): number[] | undefined {
  if (!value) return undefined;
  return value
    .split(',')
    .map((v) => Number(v.trim()))
    .filter((num) => !Number.isNaN(num));
}

function normalizeRoomStorageDriver(value: unknown): RoomStorageDriver {
  const normalized = String(value ?? '').toLowerCase();
  if (normalized === RoomStorageDriver.LIBSQL) {
    return RoomStorageDriver.LIBSQL;
  }
  return RoomStorageDriver.FILE;
}
