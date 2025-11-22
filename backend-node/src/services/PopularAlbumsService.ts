import fs from 'node:fs/promises';
import path from 'node:path';

import { PopularAlbumsConfig } from '../config/appConfig';
import { logger } from '../utils/logger';

interface LastFmTopArtistsResponse {
  artists: {
    artist: Array<{
      name: string;
    }>;
  };
}

interface LastFmTopAlbumsResponse {
  topalbums: {
    album: Array<{
      name: string;
      image?: LastFmImage[];
    }>;
  };
}

interface LastFmAlbumInfoResponse {
  album?: {
    name: string;
    artist: string;
    releasedate?: string;
    wiki?: {
      published?: string;
    };
    image?: LastFmImage[];
    url?: string;
    mbid?: string;
  };
}

interface LastFmImage {
  ['#text']?: string;
  size?: string;
}

export interface PopularAlbum {
  id: string;
  title: string;
  artist: string;
  imageUrl: string;
  externalUrl?: string;
  releaseDate?: string;
}

export interface PopularAlbumsSnapshot {
  fetchedAt: string;
  albums: PopularAlbum[];
}

export class PopularAlbumsService {
  private cache: PopularAlbumsSnapshot | null = null;
  private readonly cachePath: string;
  private refreshPromise: Promise<void> | null = null;

  // Constants from Python script
  private readonly TOP_ARTISTS_LIMIT = 10;
  private readonly ALBUMS_PER_ARTIST = 10;

  constructor(private readonly config: PopularAlbumsConfig) {
    this.cachePath = path.resolve(process.cwd(), config.cachePath);
  }

  async init(): Promise<void> {
    await this.loadCacheFromDisk();
    if (this.needsRefresh(this.cache)) {
      try {
        await this.refresh();
      } catch (error) {
        logger.warn({ error }, 'Unable to refresh popular albums cache during startup; continuing with stale data');
      }
    }
    this.scheduleRefresh();
  }

  async getSnapshot(): Promise<PopularAlbumsSnapshot> {
    if (this.needsRefresh(this.cache)) {
      try {
        await this.refresh();
      } catch (error) {
        logger.error({ error }, 'Failed to refresh popular albums cache');
      }
    }
    return this.cache ?? { fetchedAt: new Date(0).toISOString(), albums: [] };
  }

  private async refresh(): Promise<void> {
    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    this.refreshPromise = (async () => {
      let albums: PopularAlbum[] = [];
      try {
        albums = await this.fetchLatestAlbums();
      } catch (error) {
        logger.error({ error }, 'Failed to fetch from Last.fm');
      }

      if (!albums.length) {
        logger.warn('Last.fm response returned no albums, attempting fallback data');
        albums = await this.loadFallbackAlbums();
      }
      const payload: PopularAlbumsSnapshot = {
        fetchedAt: new Date().toISOString(),
        albums: this.limitAlbums(albums),
      };
      this.cache = payload;
      await this.persistCache(payload);
      await this.persistFallbackAlbums(albums);
      logger.info({ albumCount: albums.length }, 'Popular albums cache refreshed');
    })();

    try {
      await this.refreshPromise;
    } finally {
      this.refreshPromise = null;
    }
  }

  private async fetchLatestAlbums(): Promise<PopularAlbum[]> {
    if (!this.config.lastFmApiKey) {
      throw new Error('Last.fm API key missing from configuration');
    }

    logger.info('Fetching top artists...');
    const artists = await this.getTopArtists();
    logger.info({ artists }, 'Fetched top artists');

    const results: PopularAlbum[] = [];

    // Sequential processing as requested (no concurrency control needed)
    for (const artist of artists) {
      try {
        const latest = await this.processArtist(artist);
        if (latest) {
          results.push(latest);
        }
      } catch (error) {
        logger.warn({ artist, error }, 'Failed to process artist');
      }
    }

    return results;
  }

  private async getTopArtists(): Promise<string[]> {
    const params = new URLSearchParams({
      method: 'chart.gettopartists',
      limit: String(this.TOP_ARTISTS_LIMIT),
      api_key: this.config.lastFmApiKey,
      format: 'json',
    });

    const data = await this.fetchLastFm<LastFmTopArtistsResponse>(params);
    return data.artists?.artist?.map((a) => a.name) || [];
  }

  private async getArtistAlbums(artist: string): Promise<any[]> {
    const params = new URLSearchParams({
      method: 'artist.gettopalbums',
      artist: artist,
      limit: String(this.ALBUMS_PER_ARTIST),
      api_key: this.config.lastFmApiKey,
      format: 'json',
    });

    const data = await this.fetchLastFm<LastFmTopAlbumsResponse>(params);
    return data.topalbums?.album || [];
  }

  private async getAlbumInfo(artist: string, album: string): Promise<any | null> {
    try {
      const params = new URLSearchParams({
        method: 'album.getinfo',
        artist: artist,
        album: album,
        api_key: this.config.lastFmApiKey,
        format: 'json',
      });

      const data = await this.fetchLastFm<LastFmAlbumInfoResponse>(params);
      return data.album || null;
    } catch (error) {
      return null;
    }
  }

  private async processArtist(artist: string): Promise<PopularAlbum | null> {
    const albums = await this.getArtistAlbums(artist);

    let latestAlbum: any = null;
    let latestDate: Date | null = null;

    // Fetch info for all albums to find the latest one
    const infoPromises = albums.map((album) => this.getAlbumInfo(artist, album.name));
    const infos = await Promise.all(infoPromises);

    for (const info of infos) {
      if (!info) continue;

      const rawDate = info.releasedate || info.wiki?.published;
      const parsedDate = this.parseDate(rawDate);

      // If we found a date, check if it's newer
      if (parsedDate) {
        if (!latestDate || parsedDate > latestDate) {
          latestDate = parsedDate;
          latestAlbum = info;
        }
      }
    }

    // Fallback: if no release dates found, use the first album (usually the most popular)
    if (!latestAlbum && albums.length > 0) {
      latestAlbum = albums[0];
      // Ensure we have the artist name attached if it's from the simple list
      if (!latestAlbum.artist) latestAlbum.artist = artist;
    }

    if (!latestAlbum) return null;

    const image = this.pickBestImage(latestAlbum.image);
    if (!image) return null;

    return {
      id: latestAlbum.mbid || `${artist}-${latestAlbum.name}`,
      title: latestAlbum.name,
      artist: typeof latestAlbum.artist === 'string' ? latestAlbum.artist : latestAlbum.artist.name,
      imageUrl: image,
      externalUrl: latestAlbum.url,
      releaseDate: latestDate ? latestDate.toISOString() : undefined,
    };
  }

  private async fetchLastFm<T>(params: URLSearchParams): Promise<T> {
    const url = `${this.config.lastFmBaseUrl}?${params.toString()}`;
    const response = await fetch(url, {
      headers: {
        'User-Agent': 'AuroraBackend/1.0 (+https://github.com/yourusername/aurora)',
      },
    });

    if (!response.ok) {
      throw new Error(`Last.fm request failed with status ${response.status}`);
    }

    return (await response.json()) as T;
  }

  private parseDate(raw: string | undefined): Date | null {
    if (!raw) return null;
    const cleanRaw = raw.trim();

    // Try common formats
    // 1. ISO-like (e.g. "2025-02-02")
    const isoDate = new Date(cleanRaw);
    if (!isNaN(isoDate.getTime())) return isoDate;

    // 2. "02 Feb 2025, 15:33" or "02 Feb 2025"
    // Simple manual parsing or regex could be added here if needed,
    // but Date.parse handles many formats.
    // Let's try to handle the specific format from the python script if Date.parse fails.

    return null;
  }

  private pickBestImage(images?: LastFmImage[]): string | undefined {
    if (!images || !images.length) return undefined;

    const priority = ['mega', 'extralarge', 'large', 'medium', 'small'];

    for (const size of priority) {
      const img = images.find((i) => i.size === size && i['#text']);
      if (img) return img['#text'];
    }

    // Fallback to any image with text
    const anyImg = images.find((i) => i['#text']);
    return anyImg?.['#text'];
  }

  private limitAlbums(albums: PopularAlbum[]): PopularAlbum[] {
    return albums.slice(0, this.config.maxAlbums);
  }

  private async loadCacheFromDisk(): Promise<void> {
    try {
      const data = await fs.readFile(this.cachePath, 'utf-8');
      this.cache = JSON.parse(data);
    } catch (error) {
      // Ignore error if file doesn't exist
    }
  }

  private needsRefresh(cache: PopularAlbumsSnapshot | null): boolean {
    if (!cache) return true;
    const fetchedAt = new Date(cache.fetchedAt).getTime();
    const now = Date.now();
    const ageHours = (now - fetchedAt) / (1000 * 60 * 60);
    return ageHours > this.config.refreshIntervalHours;
  }

  private scheduleRefresh(): void {
    const intervalMs = this.config.refreshIntervalHours * 60 * 60 * 1000;
    setInterval(() => {
      this.refresh().catch((error) => {
        logger.error({ error }, 'Scheduled popular albums refresh failed');
      });
    }, intervalMs);
  }

  private async persistCache(payload: PopularAlbumsSnapshot): Promise<void> {
    try {
      await fs.mkdir(path.dirname(this.cachePath), { recursive: true });
      await fs.writeFile(this.cachePath, JSON.stringify(payload, null, 2), 'utf-8');
    } catch (error) {
      logger.error({ error }, 'Failed to persist popular albums cache');
    }
  }

  private async persistFallbackAlbums(albums: PopularAlbum[]): Promise<void> {
    if (!this.config.fallbackDataPath) {
      return;
    }

    if (!albums.length) {
      return;
    }

    try {
      const payload = {
        fetchedAt: new Date().toISOString(),
        albums,
      };
      const absolutePath = path.resolve(process.cwd(), this.config.fallbackDataPath);
      await fs.mkdir(path.dirname(absolutePath), { recursive: true });
      await fs.writeFile(absolutePath, JSON.stringify(payload, null, 2), 'utf-8');
    } catch (error) {
      logger.warn({ error }, 'Failed to persist fallback popular albums data');
    }
  }

  private async loadFallbackAlbums(): Promise<PopularAlbum[]> {
    if (!this.config.fallbackDataPath) {
      return [];
    }
    try {
      const absolutePath = path.resolve(process.cwd(), this.config.fallbackDataPath);
      const data = await fs.readFile(absolutePath, 'utf-8');
      const parsed = JSON.parse(data);
      return parsed.albums || [];
    } catch (error) {
      const code = (error as NodeJS.ErrnoException)?.code;
      if (code !== 'ENOENT') {
        logger.warn({ error }, 'Failed to load fallback popular albums data');
      }
      return [];
    }
  }

}
