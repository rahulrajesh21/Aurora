import { logger } from '../../../utils/logger';
import type { LyricsRequest, LyricSourceResult } from '../types';

export async function loadMusixmatchRichSync(
    params: LyricsRequest,
    signal: AbortSignal,
): Promise<LyricSourceResult | null> {
    // TODO: Implement actual Musixmatch API call
    // This requires an API key and potentially a proxy if running from a server that is blocked
    // For now, we will return null or mock data if needed

    logger.warn('Musixmatch RichSync not yet implemented');
    return null;
}
