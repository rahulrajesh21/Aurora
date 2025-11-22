import { Router } from 'express';

import { PopularAlbumsService } from '../services/PopularAlbumsService';
import { createError } from './types';
import { logger } from '../utils/logger';

export function createPopularAlbumsRoutes(popularAlbumsService: PopularAlbumsService): Router {
  const router = Router();

  router.get('/api/popular-albums', async (_req, res) => {
    try {
      const snapshot = await popularAlbumsService.getSnapshot();
      res.json(snapshot);
    } catch (error) {
      logger.error({ error }, 'Failed to serve popular albums');
      res
        .status(503)
        .json(createError('POPULAR_ALBUMS_UNAVAILABLE', 'Unable to load popular albums right now'));
    }
  });

  return router;
}
