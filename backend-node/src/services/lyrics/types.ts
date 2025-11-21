export interface LyricPart {
  startTimeMs: number;
  durationMs: number;
  words: string;
  isBackground?: boolean;
}

export interface LyricLine {
  startTimeMs: number;
  durationMs: number;
  words: string;
  parts?: LyricPart[];
  agent?: string;
  translation?: { text: string; lang: string };
  romanization?: string;
  timedRomanization?: LyricPart[];
}

export interface LyricSourceResult {
  lyrics: LyricLine[] | null;
  language?: string | null;
  source: string;
  sourceHref: string;
  musicVideoSynced?: boolean | null;
  cacheAllowed?: boolean;
  text?: string;
}

export interface LyricsResponse extends LyricSourceResult {
  song: string;
  artist: string;
  album?: string;
  durationMs: number;
  videoId: string;
  segmentMap?: SegmentMap | null;
}

export interface AudioTrackData {
  id: string;
  captionTracks: Array<{
    languageCode: string;
    languageName: string;
    kind: string;
    name: string;
    displayName: string;
    id: string | null;
    isTranslateable: boolean;
    url: string;
    vssId: string;
    isDefault: boolean;
    translationLanguage: string | null;
    captionId: string;
  }>;
}

export interface LyricsRequest {
  song: string;
  artist: string;
  videoId: string;
  durationMs: number;
  album?: string;
  audioTrackData?: AudioTrackData;
  youtubeLyricsText?: string;
  youtubeLyricsSource?: string;
}

export interface SegmentMap {
  primaryVideoId: string;
  counterpartVideoId: string;
  primaryVideoStartTimeMilliseconds: number;
  counterpartVideoStartTimeMilliseconds: number;
  durationMilliseconds: number;
}
