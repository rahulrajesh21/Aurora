import { logger } from '../../../utils/logger';
import { LRCLIB_API_URL, LRCLIB_CLIENT_HEADER } from '../constants';
import { parseLRC, parsePlainLyrics } from '../lrcUtils';
import type { LyricsRequest, LyricSourceResult } from '../types';

export interface LrclibResult {
  synced?: LyricSourceResult;
  plain?: LyricSourceResult;
}

async function requestLrclib(params: LyricsRequest, signal: AbortSignal): Promise<LrclibResult | null> {
  const url = new URL(LRCLIB_API_URL);
  url.searchParams.append('track_name', params.song);
  url.searchParams.append('artist_name', params.artist);
  if (params.album) {
    url.searchParams.append('album_name', params.album);
  }
  // LRClib expects duration in seconds, not milliseconds
  const durationSeconds = Math.round(params.durationMs / 1000);
  url.searchParams.append('duration', String(durationSeconds));

  const response = await fetch(url.toString(), {
    headers: {
      'Lrclib-Client': LRCLIB_CLIENT_HEADER,
    },
    signal,
  });

  if (!response.ok) {
    logger.warn({ status: response.status }, 'LRCLib API returned non-200');
    return null;
  }

  const data = await response.json();
  const result: LrclibResult = {};

  if (data.syncedLyrics) {
    result.synced = {
      lyrics: parseLRC(data.syncedLyrics, params.durationMs),
      source: 'LRCLib',
      sourceHref: 'https://lrclib.net',
      musicVideoSynced: false,
    };
  }

  if (data.plainLyrics) {
    result.plain = {
      lyrics: parsePlainLyrics(data.plainLyrics),
      source: 'LRCLib',
      sourceHref: 'https://lrclib.net',
      musicVideoSynced: false,
      cacheAllowed: false,
    };
  }

  return result;
}

export async function loadLrclibSynced(
  params: LyricsRequest,
  signal: AbortSignal,
  shared: Map<string, LrclibResult | null>,
): Promise<LyricSourceResult | null> {
  if (!shared.has('lrclib')) {
    shared.set('lrclib', await requestLrclib(params, signal));
  }
  return shared.get('lrclib')?.synced ?? null;
}

export async function loadLrclibPlain(
  params: LyricsRequest,
  signal: AbortSignal,
  shared: Map<string, LrclibResult | null>,
): Promise<LyricSourceResult | null> {
  if (!shared.has('lrclib')) {
    shared.set('lrclib', await requestLrclib(params, signal));
  }
  return shared.get('lrclib')?.plain ?? null;
}
