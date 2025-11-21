import { loadCubeyLyrics } from '../services/lyrics/providers/CubeyProvider';
import { logger } from '../utils/logger';
import { LyricsRequest } from '../services/lyrics/types';

async function testLyricsService() {
    logger.info('Testing CubeyProvider directly...');

    const request: LyricsRequest = {
        song: 'Thriller',
        artist: 'Michael Jackson',
        videoId: 'sOnqjkJTMaA',
        durationMs: 822000,
        album: 'Thriller'
    };

    try {
        const result = await loadCubeyLyrics(request, AbortSignal.timeout(10000), 'richsync');

        if (result) {
            logger.info({
                source: result.source,
                lyricCount: result.lyrics?.length,
                firstLine: result.lyrics?.[0]?.words
            }, 'CubeyProvider Result');
        } else {
            logger.warn('CubeyProvider returned null (likely due to auth or no lyrics)');
        }

    } catch (error) {
        logger.error({ error }, 'Error fetching lyrics');
    }
}

testLyricsService();
