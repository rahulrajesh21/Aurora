import { Request, Response, Router } from 'express';

import { ProviderType } from '../models/ProviderType';
import { QueueError, StreamingError } from '../models/StreamingError';
import { Track } from '../models/Track';
import { StreamingService } from '../services/StreamingService';
import { createError } from './types';
import { logger } from '../utils/logger';

interface AddToQueueRequest {
  trackId: string;
  provider?: string;
  addedBy?: string;
}

interface ReorderQueueRequest {
  fromPosition: number;
  toPosition: number;
}

interface QueueResponse {
  queue: Track[];
}

function respondWithQueue(res: Response, queue: Track[]): void {
  res.json({ queue } satisfies QueueResponse);
}

function parseProvider(provider?: string): ProviderType | null {
  if (!provider) {
    return ProviderType.YOUTUBE;
  }
  try {
    const normalized = provider.toUpperCase();
    return ProviderType[normalized as keyof typeof ProviderType];
  } catch {
    return null;
  }
}

export function createQueueRoutes(streamingService: StreamingService): Router {
  const router = Router();

  router.post('/api/rooms/:roomId/queue/add', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as AddToQueueRequest;
    if (!body?.trackId) {
      res.status(400).json(createError('INVALID_REQUEST', 'Track ID is required'));
      return;
    }
    const provider = parseProvider(body.provider);
    if (!provider) {
      res.status(400).json(createError('INVALID_PROVIDER', `Provider '${body.provider}' is not supported`));
      return;
    }

    try {
      await streamingService.addToQueue(roomId, body.trackId, provider);
      respondWithQueue(res, await streamingService.getQueue(roomId));
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      logger.error({ error }, 'Add to queue failed');
      res.status(500).json(createError('ADD_FAILED', error instanceof StreamingError ? error.message : 'Failed to add track to queue'));
    }
  });

  router.delete('/api/rooms/:roomId/queue/:position', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const position = Number(req.params.position);
    if (Number.isNaN(position)) {
      res.status(400).json(createError('INVALID_REQUEST', 'Invalid position parameter'));
      return;
    }
    try {
      await streamingService.removeFromQueue(roomId, position);
      respondWithQueue(res, await streamingService.getQueue(roomId));
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      logger.error({ error }, 'Remove from queue failed');
      res.status(500).json(createError('REMOVE_FAILED', 'Failed to remove track from queue'));
    }
  });

  router.put('/api/rooms/:roomId/queue/reorder', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as ReorderQueueRequest;
    if (
      typeof body?.fromPosition !== 'number' ||
      typeof body?.toPosition !== 'number' ||
      Number.isNaN(body.fromPosition) ||
      Number.isNaN(body.toPosition)
    ) {
      res.status(400).json(createError('INVALID_REQUEST', 'fromPosition and toPosition are required'));
      return;
    }
    try {
      await streamingService.reorderQueue(roomId, body.fromPosition, body.toPosition);
      respondWithQueue(res, await streamingService.getQueue(roomId));
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      logger.error({ error }, 'Reorder queue failed');
      res.status(500).json(createError('REORDER_FAILED', 'Failed to reorder queue'));
    }
  });

  router.delete('/api/rooms/:roomId/queue', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      await streamingService.clearQueue(roomId);
      respondWithQueue(res, []);
    } catch (error) {
      logger.error({ error }, 'Clear queue failed');
      res.status(500).json(createError('CLEAR_FAILED', 'Failed to clear queue'));
    }
  });

  router.post('/api/rooms/:roomId/queue/shuffle', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      await streamingService.shuffleQueue(roomId);
      respondWithQueue(res, await streamingService.getQueue(roomId));
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      logger.error({ error }, 'Shuffle queue failed');
      res.status(500).json(createError('SHUFFLE_FAILED', 'Failed to shuffle queue'));
    }
  });

  router.get('/api/rooms/:roomId/queue', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      respondWithQueue(res, await streamingService.getQueue(roomId));
    } catch (error) {
      logger.error({ error }, 'Get queue failed');
      res.status(500).json(createError('GET_QUEUE_FAILED', 'Failed to get queue'));
    }
  });

  return router;
}
