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

  constructor(youtubeApiKey?: string) {
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
      const url = `${YT_MUSIC_BASE}/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30`;

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
   * Get track details using YouTube Music player endpoint
   */
  async getTrack(trackId: string): Promise<Track | null> {
    return this.withRetry('getTrack', async () => {
      const url = `${YT_MUSIC_BASE}/player?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30`;

      const body = {
        context: this.webContext, // Use WEB_REMIX for track metadata
        videoId: trackId,
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
        logger.warn({ status: response.status, trackId }, 'Failed to fetch track from YT Music');
        return null;
      }

      const data = (await response.json()) as YTMusicPlayerResponse;
      return this.parsePlayerResponse(data, trackId);
    });
  }

  /**
   * Get stream URL using yt-dlp (handles signature decryption)
   */
  async getStreamUrl(trackId: string): Promise<StreamInfo> {
    return this.withRetry('getStreamUrl', async () => {
      const track = await this.getTrack(trackId);
      if (!track) {
        throw new TrackNotFoundError(trackId, ProviderType.YOUTUBE);
      }

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
      const process = spawn('yt-dlp', ['-f', 'bestaudio', '--get-url', '--no-playlist', `https://www.youtube.com/watch?v=${trackId}`]);

      let stdout = '';
      let stderr = '';

      process.stdout.on('data', (chunk: Buffer) => {
        stdout += chunk.toString();
      });

      process.stderr.on('data', (chunk: Buffer) => {
        stderr += chunk.toString();
      });

      process.on('error', (error: Error) => {
        logger.error({ error, trackId }, 'yt-dlp spawn error - command not found or failed to execute');
        reject(new ProviderError(ProviderType.YOUTUBE, 'yt-dlp spawn error', error));
      });

      process.on('close', (code: number | null) => {
        if (code !== 0 || !stdout.trim()) {
          logger.error({ stderr, stdout, code, trackId }, 'yt-dlp failed to extract stream URL');
          reject(new ProviderError(ProviderType.YOUTUBE, 'Failed to extract stream URL', stderr));
          return;
        }
        resolve(stdout.trim());
      });
    });
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
