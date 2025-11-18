import { spawn } from 'node:child_process';

import { MusicProvider } from './MusicProvider';
import { ProviderType } from '../models/ProviderType';
import { AudioFormat, StreamInfo } from '../models/StreamInfo';
import { Track } from '../models/Track';
import {
  AuthenticationError,
  NetworkError,
  ProviderError,
  RateLimitError,
  StreamingError,
  TrackNotFoundError,
} from '../models/StreamingError';
import { logger } from '../utils/logger';

const BASE_URL = 'https://www.googleapis.com/youtube/v3';
const MAX_RETRIES = 3;
const INITIAL_BACKOFF_MS = 1000;

interface YouTubeSearchResponse {
  items: YouTubeSearchItem[];
}

interface YouTubeSearchItem {
  id: { videoId: string };
  snippet: {
    title: string;
    channelTitle: string;
    thumbnails: {
      default: {
        url: string;
      };
    };
  };
}

interface YouTubeVideoResponse {
  items: YouTubeVideoItem[];
}

interface YouTubeVideoItem {
  id: string;
  snippet: {
    title: string;
    channelTitle: string;
    thumbnails: {
      default: {
        url: string;
      };
    };
  };
  contentDetails: {
    duration: string;
  };
}

export class YouTubeMusicProvider implements MusicProvider {
  public readonly providerType = ProviderType.YOUTUBE;

  constructor(private readonly apiKey: string) {}

  async isAvailable(): Promise<boolean> {
    return this.apiKey.length > 0;
  }

  async search(query: string, limit = 10): Promise<Track[]> {
    if (!query.trim()) {
      return [];
    }

    return this.withRetry('search', async () => {
      const url = new URL(`${BASE_URL}/search`);
      url.searchParams.set('part', 'snippet');
      url.searchParams.set('q', query);
      url.searchParams.set('type', 'video');
      url.searchParams.set('videoCategoryId', '10');
      url.searchParams.set('maxResults', String(Math.min(limit, 20)));
      url.searchParams.set('key', this.apiKey);

      const response = await fetch(url);

      if (response.status === 403) {
        const text = await response.text();
        logger.error({ text }, 'YouTube API returned 403');
        if (text.includes('quotaExceeded')) {
          throw new RateLimitError(ProviderType.YOUTUBE, 86_400);
        }
        throw new AuthenticationError(ProviderType.YOUTUBE);
      }

      if (response.status === 400) {
        const text = await response.text();
        throw new ProviderError(ProviderType.YOUTUBE, `Bad request: ${text}`);
      }

      if (!response.ok) {
        const text = await response.text();
        throw new ProviderError(ProviderType.YOUTUBE, `Search failed: ${response.status} ${text}`);
      }

      const payload = (await response.json()) as YouTubeSearchResponse;
      return payload.items
        .map((item) => this.toTrackFromSearch(item))
        .filter((track): track is Track => Boolean(track));
    });
  }

  async getTrack(trackId: string): Promise<Track | null> {
    return this.withRetry('getTrack', async () => {
      const url = new URL(`${BASE_URL}/videos`);
      url.searchParams.set('part', 'snippet,contentDetails');
      url.searchParams.set('id', trackId);
      url.searchParams.set('key', this.apiKey);

      const response = await fetch(url);
      if (!response.ok) {
        logger.warn({ status: response.status }, 'Failed to fetch video details');
        return null;
      }

      const payload = (await response.json()) as YouTubeVideoResponse;
      const video = payload.items[0];
      return video ? this.toTrackFromVideo(video) : null;
    });
  }

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

  private async withRetry<T>(operation: string, fn: () => Promise<T>): Promise<T> {
    let lastError: unknown;

    for (let attempt = 0; attempt < MAX_RETRIES; attempt += 1) {
      try {
        return await fn();
      } catch (error) {
        if (error instanceof RateLimitError || error instanceof AuthenticationError) {
          throw error;
        }
        lastError = error;
        if (attempt < MAX_RETRIES - 1) {
          const delay = INITIAL_BACKOFF_MS * 2 ** attempt;
          logger.warn({ operation, attempt: attempt + 1, delay }, 'Retrying YouTube operation');
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }
    }

    if (lastError instanceof StreamingError) {
      throw lastError;
    }

    throw new NetworkError(`${operation} failed after ${MAX_RETRIES} attempts`, lastError);
  }

  private toTrackFromSearch(item: YouTubeSearchItem): Track | null {
    try {
      const videoId = item?.id?.videoId;
      const title = item?.snippet?.title;
      const artist = item?.snippet?.channelTitle;
      const thumbnail = item?.snippet?.thumbnails?.default?.url;
      if (!videoId || !title || !artist) {
        logger.warn({ videoId, title, artist }, 'Skipping search item missing mandatory fields');
        return null;
      }
      return {
        id: videoId,
        title,
        artist,
        durationSeconds: 0,
        provider: ProviderType.YOUTUBE,
        thumbnailUrl: thumbnail,
        externalUrl: `https://www.youtube.com/watch?v=${videoId}`,
      };
    } catch (error) {
      logger.warn({ error }, 'Failed to transform search item');
      return null;
    }
  }

  private toTrackFromVideo(item: YouTubeVideoItem): Track {
    const duration = this.parseDuration(item.contentDetails.duration);
    return {
      id: item.id,
      title: item.snippet.title,
      artist: item.snippet.channelTitle,
      durationSeconds: duration,
      provider: ProviderType.YOUTUBE,
      thumbnailUrl: item.snippet.thumbnails.default.url,
      externalUrl: `https://www.youtube.com/watch?v=${item.id}`,
    };
  }

  private parseDuration(duration: string): number {
    const regex = /PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/;
    const match = duration.match(regex);
    if (!match) {
      return 0;
    }
    const [, hours, minutes, seconds] = match;
    const h = hours ? Number(hours) : 0;
    const m = minutes ? Number(minutes) : 0;
    const s = seconds ? Number(seconds) : 0;
    return h * 3600 + m * 60 + s;
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
        reject(new ProviderError(ProviderType.YOUTUBE, 'yt-dlp spawn error', error));
      });

      process.on('close', (code: number | null) => {
        if (code !== 0 || !stdout.trim()) {
          logger.error({ stderr }, 'yt-dlp failed');
          reject(new ProviderError(ProviderType.YOUTUBE, 'Failed to extract stream URL', stderr));
          return;
        }
        resolve(stdout.trim());
      });
    });
  }
}
