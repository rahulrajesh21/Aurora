import { logger } from '../../utils/logger';
import type { LyricLine, LyricPart } from './types';

const possibleIdTags = ['ti', 'ar', 'al', 'au', 'lr', 'length', 'by', 'offset', 're', 'tool', 've', '#'];

export function parseTime(timeStr: string | number): number {
  if (!timeStr) return 0;
  if (typeof timeStr === 'number') return timeStr;

  const parts = timeStr.split(':');
  let totalMs = 0;

  try {
    if (parts.length === 1) {
      totalMs = parseFloat(parts[0]) * 1000;
    } else if (parts.length === 2) {
      const minutes = parseInt(parts[0], 10);
      const seconds = parseFloat(parts[1]);
      totalMs = minutes * 60 * 1000 + seconds * 1000;
    } else if (parts.length === 3) {
      const hours = parseInt(parts[0], 10);
      const minutes = parseInt(parts[1], 10);
      const seconds = parseFloat(parts[2]);
      totalMs = hours * 3600 * 1000 + minutes * 60 * 1000 + seconds * 1000;
    }
    return Math.round(totalMs);
  } catch (error) {
    logger.warn({ timeStr, error }, 'Failed to parse time string');
    return 0;
  }
}

export function parseLRC(lrcText: string, songDuration: number): LyricLine[] {
  const lines = lrcText.split('\n');
  const result: LyricLine[] = [];
  const idTags: Record<string, string> = {};

  lines.forEach((line) => {
    let localLine = line.trim();
    const idTagMatch = localLine.match(/^[\[]([#\w]+):(.*)[\]]$/);
    if (idTagMatch && possibleIdTags.includes(idTagMatch[1])) {
      idTags[idTagMatch[1]] = idTagMatch[2];
      return;
    }

    const timeTagRegex = /[\[](\d+:\d+\.\d+)[\]]/g;
    const enhancedWordRegex = /<(\d+:\d+\.\d+)>/g;

    const timeTags: number[] = [];
    let match: RegExpExecArray | null;
    while ((match = timeTagRegex.exec(localLine)) !== null) {
      timeTags.push(parseTime(match[1]));
    }

    if (timeTags.length === 0) return;

    const lyricPart = localLine.replace(timeTagRegex, '').trim();
    const parts: LyricPart[] = [];
    let lastTime: number | null = null;
    let plainText = '';

    lyricPart.split(enhancedWordRegex).forEach((fragment, index) => {
      if (index % 2 === 0) {
        let normalized = fragment;
        if (normalized.startsWith(' ')) {
          normalized = normalized.substring(1);
        }
        if (normalized.endsWith(' ')) {
          normalized = normalized.substring(0, normalized.length - 1);
        }
        plainText += normalized;
        if (parts.length > 0 && parts[parts.length - 1].startTimeMs) {
          parts[parts.length - 1].words += normalized;
        }
      } else {
        const startTime = parseTime(fragment);
        if (lastTime !== null && parts.length > 0) {
          parts[parts.length - 1].durationMs = startTime - lastTime;
        }
        parts.push({
          startTimeMs: startTime,
          words: '',
          durationMs: 0,
        });
        lastTime = startTime;
      }
    });

    const startTime = Math.min(...timeTags);
    const endTime = Math.max(...timeTags);
    const duration = endTime - startTime;

    result.push({
      startTimeMs: startTime,
      words: plainText.trim(),
      durationMs: duration,
      parts: parts.length > 0 ? parts : undefined,
    });
  });

  result.forEach((lyric, index) => {
    if (index + 1 < result.length) {
      const nextLyric = result[index + 1];
      if (lyric.parts && lyric.parts.length > 0) {
        const lastPart = lyric.parts[lyric.parts.length - 1];
        lastPart.durationMs = nextLyric.startTimeMs - lastPart.startTimeMs;
      }
      if (lyric.durationMs === 0) {
        lyric.durationMs = nextLyric.startTimeMs - lyric.startTimeMs;
      }
    } else {
      if (lyric.parts && lyric.parts.length > 0) {
        const lastPart = lyric.parts[lyric.parts.length - 1];
        lastPart.durationMs = songDuration - lastPart.startTimeMs;
      }
      if (lyric.durationMs === 0) {
        lyric.durationMs = songDuration - lyric.startTimeMs;
      }
    }
  });

  if (idTags['offset']) {
    let offset = Number(idTags['offset']);
    if (Number.isNaN(offset)) {
      offset = 0;
      logger.warn({ offsetValue: idTags['offset'] }, 'Invalid offset in LRC');
    }
    offset *= 1000;
    result.forEach((lyric) => {
      lyric.startTimeMs -= offset;
      lyric.parts?.forEach((part) => {
        part.startTimeMs -= offset;
      });
    });
  }

  return result;
}

export function lrcFixers(lyrics: LyricLine[]): void {
  for (const lyric of lyrics) {
    if (!lyric.parts) continue;
    for (let i = 1; i < lyric.parts.length; i += 1) {
      const current = lyric.parts[i];
      const prev = lyric.parts[i - 1];
      if (current.words === ' ' && prev.words !== ' ') {
        const deltaTime = current.durationMs - prev.durationMs;
        if (Math.abs(deltaTime) <= 15 || current.durationMs <= 100) {
          const durationChange = current.durationMs;
          prev.durationMs += durationChange;
          current.durationMs -= durationChange;
          current.startTimeMs += durationChange;
        }
      }
    }
  }

  let shortDurationCount = 0;
  let durationCount = 0;
  for (const lyric of lyrics) {
    if (!lyric.parts || lyric.parts.length === 0) continue;
    for (let i = 0; i < lyric.parts.length - 2; i += 1) {
      const part = lyric.parts[i];
      if (part.words !== ' ') {
        if (part.durationMs <= 100) {
          shortDurationCount += 1;
        }
        durationCount += 1;
      }
    }
  }

  if (durationCount > 0 && shortDurationCount / durationCount > 0.5) {
    logger.info('Adjusting short LRC durations');
    for (let i = 0; i < lyrics.length; i += 1) {
      const lyric = lyrics[i];
      if (!lyric.parts || lyric.parts.length === 0) continue;

      for (let j = 0; j < lyric.parts.length; j += 1) {
        const part = lyric.parts[j];
        if (part.words === ' ') continue;
        if (part.durationMs <= 400) {
          let nextPart: LyricPart | null | undefined;
          if (j + 1 < lyric.parts.length) {
            nextPart = lyric.parts[j + 1];
          } else if (i + 1 < lyrics.length && lyrics[i + 1].parts && lyrics[i + 1].parts!.length > 0) {
            nextPart = lyrics[i + 1].parts![0];
          } else {
            nextPart = null;
          }

          if (nextPart === null) {
            part.durationMs = 300;
          } else if (nextPart.words === ' ') {
            part.durationMs += nextPart.durationMs;
            nextPart.startTimeMs += nextPart.durationMs;
            nextPart.durationMs = 0;
          } else {
            part.durationMs = nextPart.startTimeMs - part.startTimeMs;
          }
        }
      }
    }
  }
}

export function parsePlainLyrics(lyricsText: string): LyricLine[] {
  return lyricsText.split('\n').map((words) => ({
    startTimeMs: 0,
    words,
    durationMs: 0,
  }));
}

export function levenshteinDistance(a: string, b: string): number {
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;

  const matrix = [];

  for (let i = 0; i <= b.length; i++) {
    matrix[i] = [i];
  }

  for (let j = 0; j <= a.length; j++) {
    matrix[0][j] = j;
  }

  for (let i = 1; i <= b.length; i++) {
    for (let j = 1; j <= a.length; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1];
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1, // substitution
          Math.min(
            matrix[i][j - 1] + 1, // insertion
            matrix[i - 1][j] + 1 // deletion
          )
        );
      }
    }
  }

  return matrix[b.length][a.length];
}

export function calculateSimilarity(s1: string, s2: string): number {
  const longer = s1.length > s2.length ? s1 : s2;
  const shorter = s1.length > s2.length ? s2 : s1;
  const longerLength = longer.length;
  if (longerLength === 0) {
    return 1.0;
  }
  return (longerLength - levenshteinDistance(longer, shorter)) / parseFloat(longerLength.toString());
}
