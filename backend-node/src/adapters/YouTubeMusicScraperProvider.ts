import { MusicProvider } from './MusicProvider';
import { ProviderType } from '../models/ProviderType';
import { AudioFormat, StreamInfo } from '../models/StreamInfo';
import { Track } from '../models/Track';
import {
  NetworkError,
  ProviderError,
  StreamingError,
  TrackNotFoundError,
} from '../models/StreamingError';
import { logger } from '../utils/logger';
import { spawn } from 'node:child_process';
import { AppConfig } from '../config/appConfig';
import fs from 'node:fs';
import path from 'node:path';

const YT_MUSIC_BASE = 'https://music.youtube.com/youtubei/v1';
const MAX_RETRIES = 2;
const INITIAL_BACKOFF_MS = 500;

/**
 * YouTube Music Internal API Client
 * Uses the same hidden API that YouTube Music web app uses
 * This provides clean, structured metadata unlike regular YouTube API
 */

interface YTMusicContext {
  client: {
    clientName: string;
    clientVersion: string;
    hl: string;
    gl: string;
  };
}

interface YTMusicSearchResponse {
  contents?: {
    tabbedSearchResultsRenderer?: {
      tabs?: Array<{
        tabRenderer?: {
          content?: {
            sectionListRenderer?: {
              contents?: Array<{
                musicShelfRenderer?: {
                  contents?: MusicResponsiveListItem[];
                };
              }>;
            };
          };
        };
      }>;
    };
  };
}

interface MusicResponsiveListItem {
  musicResponsiveListItemRenderer?: {
    flexColumns?: FlexColumn[];
    fixedColumns?: FixedColumn[];
    thumbnail?: {
      musicThumbnailRenderer?: {
        thumbnail?: {
          thumbnails?: Thumbnail[];
        };
      };
    };
    playlistItemData?: {
      videoId?: string;
    };
    navigationEndpoint?: {
      watchEndpoint?: {
        videoId?: string;
      };
    };
  };
}

interface FlexColumn {
  musicResponsiveListItemFlexColumnRenderer?: {
    text?: {
      runs?: Run[];
    };
  };
}

interface FixedColumn {
  musicResponsiveListItemFixedColumnRenderer?: {
    text?: {
      runs?: Run[];
    };
  };
}

interface Run {
  text?: string;
  navigationEndpoint?: {
    browseEndpoint?: {
      browseId?: string;
    };
    watchEndpoint?: {
      videoId?: string;
    };
  };
}

interface Thumbnail {
  url?: string;
  width?: number;
  height?: number;
}

interface YTMusicPlayerResponse {
  videoDetails?: {
    videoId?: string;
    title?: string;
    author?: string;
    lengthSeconds?: string;
    thumbnail?: {
      thumbnails?: Thumbnail[];
    };
  };
  streamingData?: {
    formats?: StreamFormat[];
    adaptiveFormats?: StreamFormat[];
    expiresInSeconds?: string;
  };
}

interface StreamFormat {
  url?: string;
  signatureCipher?: string;
  itag?: number;
  mimeType?: string;
  bitrate?: number;
  audioQuality?: string;
  audioChannels?: number;
}

export class YouTubeMusicScraperProvider implements MusicProvider {
  public readonly providerType = ProviderType.YOUTUBE;
  private readonly context: YTMusicContext;
  private readonly webContext: YTMusicContext;
  private readonly apiKey: string;
  private readonly config: AppConfig['youtube'];
  private readonly potProviderConfig?: { enabled: boolean; port: number };

  constructor(youtubeApiKey?: string, config?: AppConfig['youtube']) {
    // Use environment variable or fallback to default key
    // Default key is from YouTube Music web app (public but may be rate-limited)
    this.apiKey = youtubeApiKey || process.env.YOUTUBE_INNERTUBE_API_KEY || 'AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30';
    this.config = config || {
      apiKey: this.apiKey,
      searchTimeout: 2000,
      searchLimit: 20,
      ytdlp: {
        cookiesPath: './cookies.txt',
        userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
    };
    this.potProviderConfig = this.config.ytdlp?.potProvider;

    // Use ANDROID_MUSIC client for stream URL extraction (better for avoiding signature issues)
    this.context = {
      client: {
        clientName: 'ANDROID_MUSIC',
        clientVersion: '6.42.52',
        hl: 'en',
        gl: 'US',
      },
    };

    // Use WEB_REMIX for search/browse (more reliable for metadata)
    this.webContext = {
      client: {
        clientName: 'WEB_REMIX',
        clientVersion: '1.20231211.01.00',
        hl: 'en',
        gl: 'US',
      },
    };
  }

  async isAvailable(): Promise<boolean> {
    // No API key required - uses public YouTube Music endpoints
    return true;
  }

  /**
   * Search using YouTube Music's internal API
   * Returns clean, structured metadata with separated title/artist
   */
  async search(query: string, limit = 10): Promise<Track[]> {
    if (!query.trim()) {
      return [];
    }

    return this.withRetry('search', async () => {
      const url = `${YT_MUSIC_BASE}/search?key=${this.apiKey}`;

      const body = {
        context: this.webContext, // Use WEB_REMIX for search
        query,
        params: 'EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D', // Filter: Songs only
      };

      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
          'Referer': 'https://music.youtube.com/',
          'Origin': 'https://music.youtube.com',
        },
        body: JSON.stringify(body),
      });

      if (!response.ok) {
        const text = await response.text();
        throw new ProviderError(
          ProviderType.YOUTUBE,
          `YouTube Music search failed: ${response.status} ${text}`
        );
      }

      const data = (await response.json()) as YTMusicSearchResponse;
      const tracks = this.parseSearchResponse(data);
      return tracks.slice(0, limit);
    });
  }

  /**
   * Get track details using yt-dlp directly
   * Skip the YouTube Music API since it's unreliable
   */
  async getTrack(trackId: string): Promise<Track | null> {
    return this.withRetry('getTrack', async () => {
      logger.info({ trackId }, 'Fetching track metadata via yt-dlp');
      return this.getTrackFromYtDlp(trackId);
    });
  }

  private async getTrackFromYtDlp(trackId: string): Promise<Track | null> {
    return new Promise((resolve) => {
      const args = this.buildYtDlpArgs(trackId);
      // Replace --get-url with --dump-json
      const getUrlIndex = args.indexOf('--get-url');
      if (getUrlIndex !== -1) {
        args[getUrlIndex] = '--dump-json';
      } else {
        args.push('--dump-json');
      }

      logger.info({ trackId }, 'Fetching track metadata via yt-dlp fallback');
      const process = spawn('yt-dlp', args);

      let stdout = '';
      let stderr = '';

      process.stdout.on('data', (chunk: Buffer) => {
        stdout += chunk.toString();
      });

      process.stderr.on('data', (chunk: Buffer) => {
        stderr += chunk.toString();
      });

      process.on('close', (code: number | null) => {
        if (code !== 0 || !stdout.trim()) {
          logger.warn({ stderr, trackId }, 'yt-dlp fallback failed to fetch metadata');
          resolve(null);
          return;
        }

        try {
          const data = JSON.parse(stdout.trim());
          const track: Track = {
            id: data.id,
            title: data.title || 'Unknown',
            artist: data.uploader || data.artist || 'Unknown Artist',
            durationSeconds: data.duration || 0,
            provider: ProviderType.YOUTUBE,
            thumbnailUrl: data.thumbnail,
            externalUrl: data.webpage_url || `https://music.youtube.com/watch?v=${trackId}`,
          };
          resolve(track);
        } catch (error) {
          logger.warn({ error, trackId }, 'Failed to parse yt-dlp JSON output');
          resolve(null);
        }
      });
    });
  }

  /**
   * Get stream URL using yt-dlp (handles signature decryption)
   */
  async getStreamUrl(trackId: string): Promise<StreamInfo> {
    return this.withRetry('getStreamUrl', async () => {
      const track = await this.getTrack(trackId);
      if (!track) {
        logger.error({ trackId }, 'Failed to fetch track metadata from YouTube Music API');
        throw new TrackNotFoundError(trackId, ProviderType.YOUTUBE);
      }

      logger.info({ trackId, trackTitle: track.title }, 'Track metadata fetched, extracting stream URL with yt-dlp');
      const streamUrl = await this.extractStreamUrl(trackId);
      if (!streamUrl) {
        throw new ProviderError(ProviderType.YOUTUBE, 'Failed to extract stream URL');
      }

      return {
        streamUrl,
        track,
        expiresAt: Date.now() + 6 * 60 * 60 * 1000,
        format: AudioFormat.WEBM,
      };
    });
  }

  /**
   * Parse YouTube Music search response
   * Extracts clean metadata from structured response
   */
  private parseSearchResponse(data: YTMusicSearchResponse): Track[] {
    const tracks: Track[] = [];

    try {
      const tabs = data.contents?.tabbedSearchResultsRenderer?.tabs || [];
      for (const tab of tabs) {
        const sections = tab.tabRenderer?.content?.sectionListRenderer?.contents || [];
        for (const section of sections) {
          const items = section.musicShelfRenderer?.contents || [];
          for (const item of items) {
            const track = this.parseSearchItem(item);
            if (track) {
              tracks.push(track);
            }
          }
        }
      }
    } catch (error) {
      logger.warn({ error }, 'Failed to parse YouTube Music search response');
    }

    return tracks;
  }

  /**
   * Parse individual search result item
   * YouTube Music provides metadata in separate columns:
   * - Column 0: Song title (clean)
   * - Column 1: Artists (separated)
   * - Column 2: Album (if available)
   * - Fixed Column: Duration
   */
  private parseSearchItem(item: MusicResponsiveListItem): Track | null {
    try {
      const renderer = item.musicResponsiveListItemRenderer;
      if (!renderer) return null;

      const flexColumns = renderer.flexColumns || [];
      const fixedColumns = renderer.fixedColumns || [];

      // Extract video ID
      const videoId =
        renderer.playlistItemData?.videoId ||
        renderer.navigationEndpoint?.watchEndpoint?.videoId ||
        flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.navigationEndpoint
          ?.watchEndpoint?.videoId;

      if (!videoId) return null;

      // Column 0: Clean song title
      const title = flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text;
      if (!title) return null;

      // Column 1: Artists (clean, separated from title)
      const artistRuns = flexColumns[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
      const artists: string[] = [];
      for (let i = 0; i < artistRuns.length; i += 2) {
        const artistName = artistRuns[i]?.text;
        if (artistName) {
          artists.push(artistName);
        }
      }
      const artist = artists.join(', ') || 'Unknown Artist';

      // Duration from fixed column
      const durationText =
        fixedColumns[0]?.musicResponsiveListItemFixedColumnRenderer?.text?.runs?.[0]?.text || '0:00';
      const durationSeconds = this.parseDuration(durationText);

      // Thumbnail
      const thumbnails = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
      const thumbnailUrl = this.getBestThumbnail(thumbnails);

      return {
        id: videoId,
        title, // Clean title without artist names or extras
        artist, // Clean artist name(s)
        durationSeconds,
        provider: ProviderType.YOUTUBE,
        thumbnailUrl,
        externalUrl: `https://music.youtube.com/watch?v=${videoId}`,
      };
    } catch (error) {
      logger.warn({ error }, 'Failed to parse search item');
      return null;
    }
  }

  private parsePlayerResponse(data: YTMusicPlayerResponse, trackId: string): Track | null {
    try {
      const details = data.videoDetails;
      if (!details) return null;

      const durationSeconds = details.lengthSeconds ? parseInt(details.lengthSeconds, 10) : 0;
      const thumbnails = details.thumbnail?.thumbnails || [];

      return {
        id: trackId,
        title: details.title || 'Unknown',
        artist: details.author || 'Unknown Artist',
        durationSeconds,
        provider: ProviderType.YOUTUBE,
        thumbnailUrl: this.getBestThumbnail(thumbnails),
        externalUrl: `https://music.youtube.com/watch?v=${trackId}`,
      };
    } catch (error) {
      logger.warn({ error, trackId }, 'Failed to parse player response');
      return null;
    }
  }



  private getBestThumbnail(thumbnails: Thumbnail[]): string | undefined {
    if (!thumbnails.length) return undefined;

    // Sort by size, prefer larger thumbnails
    const sorted = [...thumbnails].sort((a, b) => (b.width || 0) - (a.width || 0));
    return sorted[0]?.url;
  }

  private parseDuration(durationText: string): number {
    const parts = durationText.split(':').map((p) => parseInt(p, 10));
    if (parts.length === 2) {
      return parts[0] * 60 + parts[1]; // MM:SS
    }
    if (parts.length === 3) {
      return parts[0] * 3600 + parts[1] * 60 + parts[2]; // HH:MM:SS
    }
    return 0;
  }

  private extractStreamUrl(trackId: string): Promise<string | null> {
    return new Promise((resolve, reject) => {
      const args = this.buildYtDlpArgs(trackId);
      args.push('--get-url');

      logger.info({ trackId, args: args.join(' ') }, 'Executing yt-dlp command with anti-bot measures');
      const ytdlpProcess = spawn('yt-dlp', args);

      let stdout = '';
      let stderr = '';

      ytdlpProcess.stdout.on('data', (chunk: Buffer) => {
        stdout += chunk.toString();
      });

      ytdlpProcess.stderr.on('data', (chunk: Buffer) => {
        stderr += chunk.toString();
      });

      ytdlpProcess.on('error', (error: Error) => {
        logger.error({ error, trackId }, 'yt-dlp spawn error - command not found or failed to execute');
        reject(new ProviderError(ProviderType.YOUTUBE, 'yt-dlp spawn error', error));
      });

      ytdlpProcess.on('close', (code: number | null) => {
        if (code !== 0 || !stdout.trim()) {
          logger.error({ stderr, stdout, code, trackId }, 'yt-dlp failed to extract stream URL');
          reject(new ProviderError(ProviderType.YOUTUBE, 'Failed to extract stream URL', stderr));
          return;
        }
        resolve(stdout.trim());
      });
    });
  }

  private buildYtDlpArgs(trackId: string): string[] {
    const args = [
      '-f', 'bestaudio',
      '--no-playlist',
      '--geo-bypass',
      '--force-ipv4',
    ];

    // Add user-agent
    const userAgent = this.config.ytdlp?.userAgent || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
    args.push('--user-agent', userAgent);

    // Add cookies from environment variable or file
    const cookiesFromEnv = process.env.YTDLP_COOKIES;
    if (cookiesFromEnv) {
      // Write cookies from env to temporary file
      const tempCookiesPath = path.join(process.cwd(), '.cookies-temp.txt');
      try {
        fs.writeFileSync(tempCookiesPath, cookiesFromEnv, 'utf-8');
        args.push('--cookies', tempCookiesPath);
      } catch (error) {
        logger.error({ error }, 'Failed to write cookies from environment variable');
      }
    } else {
      // Fallback to cookies file path
      const cookiesPath = this.config.ytdlp?.cookiesPath;
      if (cookiesPath) {
        const resolvedCookiesPath = path.resolve(process.cwd(), cookiesPath);
        if (fs.existsSync(resolvedCookiesPath)) {
          args.push('--cookies', resolvedCookiesPath);
        }
      }
    }

    // Add PO token if available
    if (this.config.ytdlp?.poToken) {
      args.push('--extractor-args', `youtube:po_token=${this.config.ytdlp.poToken}`);
    }

    // Add visitor data if available
    if (this.config.ytdlp?.visitorData) {
      args.push('--extractor-args', `youtube:visitor_data=${this.config.ytdlp.visitorData}`);
    }

    // Add POT provider args if enabled
    if (this.potProviderConfig?.enabled) {
      const port = this.potProviderConfig.port || 4416;
      args.push('--extractor-args', `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${port}`);
    }

    // Add other headers and options
    args.push(
      '--add-header', 'Accept-Language:en-US,en;q=0.9',
      // Use default client as recommended by yt-dlp for avoiding bot detection
      '--extractor-args', 'youtube:player_client=default',
      // Enable JavaScript runtime support (uses Node.js)
      '--js-runtimes', 'node',
      // Allow downloading remote JavaScript challenge solver from GitHub
      '--remote-components', 'ejs:github',
      '--no-check-certificates',
      `https://music.youtube.com/watch?v=${trackId}`
    );

    return args;
  }

  private async withRetry<T>(operation: string, fn: () => Promise<T>): Promise<T> {
    let lastError: unknown;

    for (let attempt = 0; attempt < MAX_RETRIES; attempt += 1) {
      try {
        return await fn();
      } catch (error) {
        lastError = error;
        if (attempt < MAX_RETRIES - 1) {
          const delay = INITIAL_BACKOFF_MS * 2 ** attempt;
          logger.warn(
            { operation, attempt: attempt + 1, delay },
            'Retrying YouTube Music operation'
          );
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }
    }

    if (lastError instanceof StreamingError) {
      throw lastError;
    }

    throw new NetworkError(`${operation} failed after ${MAX_RETRIES} attempts`, lastError);
  }
}
