import { Router, type Request, type Response } from 'express';

import { LyricsService } from '../services/lyrics/LyricsService';
import { createError } from './types';
import { logger } from '../utils/logger';
import type { LyricsRequest } from '../services/lyrics/types';

export function createLyricsRoutes(lyricsService: LyricsService): Router {
  const router = Router();

  router.post('/api/lyrics', async (req: Request, res: Response) => {
    const body = req.body as LyricsRequest;
    if (!body?.song || !body?.artist || !body?.videoId || !body?.durationMs) {
      res.status(400).json(createError('INVALID_REQUEST', 'song, artist, videoId, and durationMs are required'));
      return;
    }

    try {
      const payload = await lyricsService.getLyrics(body);
      res.json(payload);
    } catch (error) {
      logger.error({ error }, 'Failed to fetch synced lyrics');
      res.status(500).json(createError('LYRICS_ERROR', 'Unable to fetch lyrics for this track'));
    }
  });

  return router;
}
