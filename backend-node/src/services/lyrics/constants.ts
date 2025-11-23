export const LYRICS_API_URL = 'https://lyrics-api-go-better-lyrics-api-pr-12.up.railway.app/getLyrics';
export const LRCLIB_API_URL = 'https://lrclib.net/api/get';
export const LRCLIB_CLIENT_HEADER = 'BetterLyrics Server (https://github.com/better-lyrics/better-lyrics)';
export const LRCLIB_UPLOAD_URL = 'https://lrclibup.boidu.dev/';
export const CACHE_VERSION = '1.3.0';
export const CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const NO_LYRICS_TEXT = 'No lyrics found for this song';
export const PROVIDER_PRIORITY = [
  'bLyrics-richsynced',
  'bLyrics-synced',
] as const;
export type ProviderKey = (typeof PROVIDER_PRIORITY)[number];
