import { Request, Response, Router } from 'express';

import { StreamingService } from '../services/StreamingService';
import { NetworkError, ProviderError, StreamingError } from '../models/StreamingError';
import { createError } from './types';
import { logger } from '../utils/logger';

interface SearchRequest {
  query: string;
}

export function createSearchRoutes(streamingService: StreamingService): Router {
  const router = Router();

  router.post('/api/search', async (req: Request, res: Response) => {
    const body = req.body as SearchRequest;
    if (!body?.query?.trim()) {
      res.status(400).json(createError('INVALID_REQUEST', 'Search query cannot be blank'));
      return;
    }

    try {
      logger.info({ query: body.query }, 'Processing search request');
      const result = await streamingService.search(body.query);
      res.json(result);
    } catch (error) {
      if (error instanceof NetworkError) {
        res.status(503).json(createError('NETWORK_ERROR', error.message));
        return;
      }
      if (error instanceof ProviderError) {
        res.status(503).json(createError('PROVIDER_ERROR', error.message));
        return;
      }
      if (error instanceof StreamingError) {
        res.status(500).json(createError('SEARCH_FAILED', error.message));
        return;
      }
      logger.error({ error }, 'Unexpected error in search endpoint');
      res.status(500).json(createError('INTERNAL_ERROR', 'An unexpected error occurred'));
    }
  });

  return router;
}
