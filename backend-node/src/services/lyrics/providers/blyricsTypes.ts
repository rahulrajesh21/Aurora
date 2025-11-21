export interface TimedWord {
  '#text': string;
}

export interface LyricSpan {
  '#text'?: string;
  span?: TimedWord[];
  ':@'?: {
    '@_begin': string;
    '@_end': string;
  };
}

export interface MetadataTextContainer {
  '#text'?: string;
  span?: LyricSpan[];
  ':@'?: {
    '@_begin'?: string;
    '@_end'?: string;
    '@_role'?: string;
  };
}

interface ParagraphAttributes {
  '@_begin': string;
  '@_end': string;
  '@_key': string;
  '@_agent': string;
  '@_role': string;
}

export interface SpanElement {
  '#text'?: string;
  span?: LyricSpan[];
  ':@': ParagraphAttributes;
}

export type ParagraphElementOrBackground = SpanElement & {
  span?: SpanElement[];
};

interface DivAttributes {
  '@_begin': string;
  '@_end': string;
  '@_songPart': string;
  '@_agent'?: string;
}

export interface DivElement {
  p: ParagraphElementOrBackground[];
  ':@': DivAttributes;
}

interface BodyElement {
  div: DivElement[];
  ':@'?: {
    '@_dur': string;
  };
}

interface TranslationItem {
  text: MetadataTextContainer[];
  ':@': {
    '@_for': string;
  };
}

interface TranslationContainer {
  translation: TranslationItem[];
  ':@': {
    '@_type': string;
    '@_lang': string;
  };
}

interface TransliterationItem {
  text: ParagraphElementOrBackground[];
  ':@': {
    '@_for': string;
  };
}

interface TransliterationContainer {
  transliteration: TransliterationItem[];
  ':@': {
    '@_lang': string;
  };
}

interface ITunesMetadata {
  translations?: TranslationContainer[];
  transliterations?: TransliterationContainer[];
}

interface MetadataAttributes {
  '@_type'?: string;
  '@_id'?: string;
  '@_leadingSilence'?: string;
}

interface MetadataElement {
  agent?: unknown[];
  ':@': MetadataAttributes;
  iTunesMetadata?: ITunesMetadata[];
}

interface HeadElement {
  metadata: MetadataElement[];
}

interface TtmlElement {
  head?: HeadElement[];
  body?: BodyElement[];
  ':@': {
    '@_timing': string;
    '@_lang': string;
  };
}

interface TtmlRootObject {
  tt: TtmlElement[];
}

export type TtmlRoot = TtmlRootObject[];
