import type { LyricsRequest, LyricSourceResult } from '../types';
import { logger } from '../../../utils/logger';

export async function loadYoutubeCaptions(params: LyricsRequest, signal: AbortSignal): Promise<LyricSourceResult | null> {
  const { audioTrackData } = params;
  if (!audioTrackData || audioTrackData.captionTracks.length === 0) {
    return null;
  }

  let langCode: string | null = null;
  if (audioTrackData.captionTracks.length === 1) {
    langCode = audioTrackData.captionTracks[0].languageCode;
  } else {
    for (const track of audioTrackData.captionTracks) {
      if (track.displayName.includes('auto-generated')) {
        langCode = track.languageCode;
        break;
      }
    }
  }

  if (!langCode) {
    logger.warn('Could not determine caption language');
    return null;
  }

  let captionsUrl: URL | null = null;
  for (const track of audioTrackData.captionTracks) {
    if (!track.displayName.includes('auto-generated') && track.languageCode === langCode) {
      captionsUrl = new URL(track.url);
      break;
    }
  }

  if (!captionsUrl) {
    logger.info('Only auto-generated captions available; skipping');
    return null;
  }

  captionsUrl.searchParams.set('fmt', 'json3');
  const response = await fetch(captionsUrl.toString(), { signal });
  if (!response.ok) {
    logger.warn({ status: response.status }, 'Failed to fetch YouTube captions');
    return null;
  }

  const captionData = await response.json();
  if (!Array.isArray(captionData?.events)) {
    return null;
  }

  const lyrics = captionData.events
    .map((event: { segs: Array<{ utf8: string }>; tStartMs: number; dDurationMs: number }) => {
      const words = event.segs.map((seg) => seg.utf8).join(' ').replace(/\n/g, ' ').trim();
      return {
        startTimeMs: event.tStartMs,
        words,
        durationMs: event.dDurationMs,
      };
    })
    .filter((entry: { words: string }) => entry.words.length > 0);

  if (!lyrics.length) {
    return null;
  }

  return {
    lyrics,
    language: langCode,
    source: 'YouTube Captions',
    sourceHref: '',
    musicVideoSynced: true,
  };
}
