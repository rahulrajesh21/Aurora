import { XMLParser, type X2jOptions } from 'fast-xml-parser';

import { logger } from '../../../utils/logger';
import type { LyricsRequest, LyricSourceResult, LyricLine, LyricPart } from '../types';
import { parseTime } from '../lrcUtils';
import { LYRICS_API_URL } from '../constants';
import type { ParagraphElementOrBackground, SpanElement, TtmlRoot } from './blyricsTypes';

export interface BLyricsResult {
  richSync?: LyricSourceResult;
  lineSync?: LyricSourceResult;
}

function parseLyricPart(p: ParagraphElementOrBackground[], beginTime: number, ignoreSpanSpace = false) {
  let text = '';
  const parts: LyricPart[] = [];
  let isWordSynced = false;

  p.forEach((paragraph) => {
    let isBackground = false;
    let localParagraph: SpanElement[] = [paragraph];

    if (paragraph[':@'] && paragraph[':@']['@_role'] === 'x-bg' && paragraph.span) {
      isBackground = true;
      localParagraph = paragraph.span;
    }

    for (const subPart of localParagraph) {
      if (subPart['#text'] && (!ignoreSpanSpace || localParagraph.length <= 1)) {
        text += subPart['#text'];
        const lastPart = parts[parts.length - 1];
        parts.push({
          startTimeMs: lastPart ? lastPart.startTimeMs + lastPart.durationMs : beginTime,
          durationMs: 0,
          words: subPart['#text'],
          isBackground,
        });
      } else if (subPart.span && subPart.span.length > 0) {
        const spanText = subPart.span[0]['#text'] ?? '';
        const startTimeMs = parseTime(subPart[':@']['@_begin']);
        const endTimeMs = parseTime(subPart[':@']['@_end']);

        parts.push({
          startTimeMs,
          durationMs: endTimeMs - startTimeMs,
          isBackground,
          words: spanText,
        });
        text += spanText;
        isWordSynced = true;
      }
    }
  });

  if (!isWordSynced) {
    parts.splice(0, parts.length);
  }

  return { parts, text, isWordSynced };
}

function buildLyricResult(ttml: TtmlRoot): { lyrics: LyricLine[]; language: string; isWordSynced: boolean } {
  const tt = ttml[0].tt;
  const ttHead = tt.find((entry) => entry.head)?.head;
  const ttBodyContainer = tt.find((entry) => entry.body)!;
  const ttBody = ttBodyContainer.body!;
  const ttMeta = ttBodyContainer[':@'];
  const lines = ttBody.flatMap((entry) => entry.div);

  const lyrics: LyricLine[] = [];
  let isWordSynced = false;

  lines.forEach((line) => {
    const meta = line[':@'];
    const beginTimeMs = parseTime(meta['@_begin']);
    const endTimeMs = parseTime(meta['@_end']);

    const parsed = parseLyricPart(line.p, beginTimeMs);
    if (parsed.isWordSynced) {
      isWordSynced = true;
    }

    lyrics.push({
      agent: meta['@_agent'],
      durationMs: endTimeMs - beginTimeMs,
      parts: parsed.parts,
      startTimeMs: beginTimeMs,
      words: parsed.text,
    });
  });

  if (ttHead) {
    const metadata = ttHead[0].metadata.find((entry) => entry.iTunesMetadata);
    if (metadata) {
      const translations = metadata.iTunesMetadata?.find((entry) => entry.translations)?.translations;
      const transliterations = metadata.iTunesMetadata?.find((entry) => entry.transliterations)?.transliterations;

      if (translations && translations.length > 0) {
        const lang = translations[0][':@']['@_lang'];
        translations[0].translation.forEach((translation) => {
          const text = translation.text[0]['#text'];
          const targetLine = translation[':@']['@_for'];
          if (lang && text && targetLine?.startsWith('L')) {
            const index = Number(targetLine.substring(1)) - 1;
            if (index < lyrics.length) {
              lyrics[index].translation = { text, lang };
            }
          }
        });
      }

      if (transliterations && transliterations.length > 0) {
        transliterations[0].transliteration.forEach((transliteration) => {
          const targetLine = transliteration[':@']['@_for'];
          if (targetLine?.startsWith('L')) {
            const index = Number(targetLine.substring(1)) - 1;
            if (index < lyrics.length) {
              const beginTime = lyrics[index].startTimeMs;
              const parsed = parseLyricPart(transliteration.text, beginTime, false);
              lyrics[index].romanization = parsed.text;
              lyrics[index].timedRomanization = parsed.parts;
            }
          }
        });
      }
    }
  }

  return { lyrics, language: ttMeta?.['@_lang'] ?? 'en', isWordSynced };
}

async function requestBlyrics(params: LyricsRequest, signal: AbortSignal): Promise<BLyricsResult | null> {
  const url = new URL(LYRICS_API_URL);
  url.searchParams.append('s', params.song);
  url.searchParams.append('a', params.artist);
  url.searchParams.append('d', String(params.durationMs));
  if (params.album) {
    url.searchParams.append('al', params.album);
  }

  const response = await fetch(url.toString(), { signal });
  if (!response.ok) {
    logger.warn({ status: response.status }, 'bLyrics API returned non-200');
    return null;
  }

  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: '@_',
    attributesGroupName: false,
    textNodeName: '#text',
    trimValues: false,
    removeNSPrefix: true,
    preserveOrder: true,
    allowBooleanAttributes: true,
    parseAttributeValue: false,
    parseTagValue: false,
  } satisfies X2jOptions);

  const payload = await response.json();
  const ttml = (parser.parse(payload.ttml) as TtmlRoot) ?? [];
  if (!ttml.length) {
    return null;
  }

  const { lyrics, language, isWordSynced } = buildLyricResult(ttml);
  const baseResult: LyricSourceResult = {
    cacheAllowed: true,
    language,
    lyrics,
    musicVideoSynced: false,
    source: 'boidu.dev',
    sourceHref: 'https://boidu.dev/',
  };

  if (isWordSynced) {
    return { richSync: baseResult };
  }
  return { lineSync: baseResult };
}

export async function loadBlyricsRichSync(
  params: LyricsRequest,
  signal: AbortSignal,
  shared: Map<string, BLyricsResult | null>,
): Promise<LyricSourceResult | null> {
  if (!shared.has('blyrics')) {
    shared.set('blyrics', await requestBlyrics(params, signal));
  }
  return shared.get('blyrics')?.richSync ?? null;
}

export async function loadBlyricsLineSync(
  params: LyricsRequest,
  signal: AbortSignal,
  shared: Map<string, BLyricsResult | null>,
): Promise<LyricSourceResult | null> {
  if (!shared.has('blyrics')) {
    shared.set('blyrics', await requestBlyrics(params, signal));
  }
  return shared.get('blyrics')?.lineSync ?? null;
}
