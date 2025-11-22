import { logger } from '../../utils/logger';
import type { LyricsRequest } from './types';

const OPENROUTER_URL = 'https://openrouter.ai/api/v1/chat/completions';
const GEMMA_MODEL = 'google/gemma-3-12b-it';
const REFERER = 'https://github.com/rahulrajesh21/Aurora';
const CLIENT_TITLE = 'Aurora Better Lyrics Service';

export interface NormalizedMetadata {
  artist: string;
  song: string;
}

interface OpenRouterMessage {
  role: string;
  content?: string | Array<{ type?: string; text?: string }>;
}

interface OpenRouterChoice {
  index: number;
  message?: OpenRouterMessage;
}

interface OpenRouterResponse {
  choices?: OpenRouterChoice[];
}

type FetchLike = typeof fetch;

export class GemmaMetadataService {
  private cache = new Map<string, NormalizedMetadata>();

  constructor(
    private readonly apiKey = process.env.OPENROUTER_API_KEY ?? process.env.GEMMA_API_KEY ?? '',
    private readonly fetchImpl: FetchLike = fetch,
  ) {}

  async normalizeMetadata(request: Pick<LyricsRequest, 'song' | 'artist'>): Promise<NormalizedMetadata | null> {
    const title = request.song?.trim();
    if (!title || !this.apiKey) {
      return null;
    }

    const cached = this.cache.get(title);
    if (cached) {
      return cached;
    }

    try {
      const response = await this.fetchImpl(OPENROUTER_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${this.apiKey}`,
          'HTTP-Referer': REFERER,
          'X-Title': CLIENT_TITLE,
        },
        body: JSON.stringify({
          model: GEMMA_MODEL,
          temperature: 0.2,
          messages: [
            {
              role: 'system',
              content:
                'You extract artist and song names from noisy track titles. Respond ONLY with valid JSON that matches the schema {"artist name":"...","song title":"..."}. Never include commentary.',
            },
            {
              role: 'user',
              content: this.buildPrompt(title, request.artist),
            },
          ],
        }),
      });

      if (!response.ok) {
        logger.warn({ status: response.status }, 'Gemma metadata request failed');
        return null;
      }

      const payload = (await response.json()) as OpenRouterResponse;
      const content = this.extractContent(payload);
      if (!content) {
        return null;
      }

      const parsed = this.parseJson(content);
      if (!parsed) {
        return null;
      }

      const artistRaw = (parsed['artist name'] ?? parsed.artist ?? parsed['artist_name']) as string | undefined;
      const songRaw = (parsed['song title'] ?? parsed.title ?? parsed['song_title']) as string | undefined;

      const normalized: NormalizedMetadata = {
        artist: (artistRaw?.trim() || request.artist?.trim() || title).trim(),
        song: (songRaw?.trim() || title).trim(),
      };

      this.remember(title, normalized);
      return normalized;
    } catch (error) {
      logger.warn({ error }, 'Gemma metadata service failed');
      return null;
    }
  }

  private buildPrompt(title: string, artist?: string): string {
    const artistLine = artist?.trim() ? `Known artist metadata: ${artist.trim()}` : 'Known artist metadata: unknown';
    return [
      'Input is a noisy streaming title. Extract canonical artist and song names.',
      'Return valid JSON exactly in the shape {"artist name":"...","song title":"..."}.',
      'Do not include additional keys or prose.',
      `Title: ${title}`,
      artistLine,
    ].join('\n');
  }

  private extractContent(response: OpenRouterResponse): string | null {
    const choice = response.choices?.[0];
    if (!choice?.message?.content) {
      return null;
    }

    if (typeof choice.message.content === 'string') {
      return choice.message.content;
    }

    return choice.message.content.map((part) => part?.text ?? '').join('').trim() || null;
  }

  private parseJson(raw: string): Record<string, unknown> | null {
    const trimmed = raw.trim();
    const attempt = this.tryParse(trimmed);
    if (attempt) {
      return attempt;
    }

    const firstBrace = trimmed.indexOf('{');
    const lastBrace = trimmed.lastIndexOf('}');
    if (firstBrace !== -1 && lastBrace !== -1 && lastBrace > firstBrace) {
      return this.tryParse(trimmed.substring(firstBrace, lastBrace + 1));
    }

    return null;
  }

  private tryParse(value: string): Record<string, unknown> | null {
    try {
      return JSON.parse(value);
    } catch {
      return null;
    }
  }

  private remember(key: string, value: NormalizedMetadata) {
    this.cache.set(key, value);
    if (this.cache.size > 100) {
      const first = this.cache.keys().next().value;
      if (first) {
        this.cache.delete(first);
      }
    }
  }
}
