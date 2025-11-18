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
  };
}

const DEFAULTS: AppConfig = {
  youtube: {
    apiKey: '',
    searchTimeout: 2000,
    searchLimit: 20,
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

  return {
    youtube: {
      apiKey: youtubeApiKey,
      searchTimeout: Number(process.env.YOUTUBE_SEARCH_TIMEOUT ?? streaming.youtube?.searchTimeout ?? DEFAULTS.youtube.searchTimeout),
      searchLimit: Number(process.env.YOUTUBE_SEARCH_LIMIT ?? streaming.youtube?.searchLimit ?? DEFAULTS.youtube.searchLimit),
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
