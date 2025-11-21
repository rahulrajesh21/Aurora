import { parsePlainLyrics } from '../lrcUtils';
import type { LyricsRequest, LyricSourceResult } from '../types';

export async function loadYoutubeLyrics(params: LyricsRequest): Promise<LyricSourceResult | null> {
  if (!params.youtubeLyricsText || !params.youtubeLyricsSource) {
    return null;
  }

  return {
    lyrics: parsePlainLyrics(params.youtubeLyricsText),
    source: params.youtubeLyricsSource,
    sourceHref: '',
    musicVideoSynced: false,
    cacheAllowed: false,
    text: params.youtubeLyricsText,
  };
}
