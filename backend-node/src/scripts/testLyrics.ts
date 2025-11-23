import { LyricsService } from '../services/lyrics/LyricsService';
import { loadConfig } from '../config/appConfig';
import { logger } from '../utils/logger';
import { LyricsRequest } from '../services/lyrics/types';

async function testLyricsService() {
    logger.info('Testing Better Lyrics provider stack via LyricsService...');

    const request: LyricsRequest = {
        song: 'Thriller',
        artist: 'Michael Jackson',
        videoId: 'sOnqjkJTMaA',
        durationMs: 822000,
        album: 'Thriller'
    };

    try {
        const config = loadConfig();
        const service = new LyricsService(config);
        const result = await service.getLyrics(request);

        logger.info({
            source: result.source,
            lyricCount: result.lyrics?.length,
            firstLine: result.lyrics?.[0]?.words
        }, 'LyricsService Result');

    } catch (error) {
        logger.error({ error }, 'Error fetching lyrics');
    }
}

testLyricsService();
