import { Request, Response, Router } from 'express';

import { ProviderType } from '../models/ProviderType';
import {
  InvalidSeekPosition,
  NetworkError,
  ProviderError,
  QueueError,
  StreamingError,
  TrackNotFoundError,
} from '../models/StreamingError';
import { StreamingService } from '../services/StreamingService';
import { createError } from './types';
import { StreamProxyController } from '../controllers/StreamProxyController';
import { logger } from '../utils/logger';

interface PlayRequest {
  trackId?: string;
  provider?: ProviderType;
}

interface SeekRequest {
  positionSeconds?: number;
  percentage?: number;
}

interface PauseRequest {
  positionSeconds?: number;
}

export function createPlaybackRoutes(streamingService: StreamingService): Router {
  const router = Router();

  router.post('/api/rooms/:roomId/playback/play', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as PlayRequest | undefined;
    try {
      if (body?.trackId && body.provider) {
        const state = await streamingService.play(roomId, body.trackId, body.provider);
        res.json(state);
        return;
      }
      const state = await streamingService.resume(roomId);
      res.json(state);
    } catch (error) {
      handlePlaybackError(res, error);
    }
  });

  router.post('/api/rooms/:roomId/playback/pause', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as PauseRequest | undefined;
    try {
      const state = await streamingService.pause(roomId, body?.positionSeconds);
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'PAUSE_FAILED', 'Pause failed');
    }
  });

  router.post('/api/rooms/:roomId/playback/resume', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const state = await streamingService.resume(roomId);
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'RESUME_FAILED', 'Resume failed');
    }
  });

  router.post('/api/rooms/:roomId/playback/skip', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const state = await streamingService.skip(roomId);
      res.json(state);
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      handleGenericError(res, error, 'SKIP_FAILED', 'Skip failed');
    }
  });

  router.get('/api/rooms/:roomId/playback/state', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const state = await streamingService.getState(roomId);
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'STATE_FAILED', 'Failed to fetch playback state');
    }
  });

  const streamProxyController = new StreamProxyController(streamingService);

  router.get('/api/playback/stream/:trackId', streamProxyController.streamAudio);

  router.get('/api/playback/stream/:trackId/info', async (req: Request, res: Response) => {
    const { trackId } = req.params;
    if (!trackId) {
      res.status(400).json(createError('INVALID_REQUEST', 'Track ID is required'));
      return;
    }

    try {
      // Return the proxy URL instead of the raw stream URL
      // The client should use this URL to stream audio through our backend proxy
      // Always use https in production (Railway forwards http->https but req.protocol stays 'http')
      const protocol = req.get('x-forwarded-proto') || req.protocol || 'https';
      const proxyUrl = `${protocol}://${req.get('host')}/api/playback/stream/${trackId}`;
      res.json({ streamUrl: proxyUrl });
    } catch (error) {
      logger.error({ error }, 'Failed to fetch stream URL info');
      res.status(503).json(createError('STREAM_ERROR', 'Failed to get stream URL'));
    }
  });

  router.post('/api/rooms/:roomId/playback/seek', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    const body = req.body as SeekRequest;
    if (!body.positionSeconds && !body.percentage && body.positionSeconds !== 0) {
      res.status(400).json(createError('INVALID_REQUEST', 'Either positionSeconds or percentage must be provided'));
      return;
    }
    if (body.positionSeconds !== undefined && body.percentage !== undefined) {
      res.status(400).json(createError('INVALID_REQUEST', 'Only one of positionSeconds or percentage should be provided'));
      return;
    }

    try {
      const state = body.positionSeconds !== undefined
        ? await streamingService.seek(roomId, body.positionSeconds)
        : await streamingService.seekByPercentage(roomId, body.percentage!);
      res.json(state);
    } catch (error) {
      if (error instanceof InvalidSeekPosition) {
        res.status(400).json(createError('INVALID_SEEK_POSITION', error.message));
        return;
      }
      if (error instanceof NetworkError) {
        res.status(503).json(createError('NETWORK_ERROR', error.message));
        return;
      }
      handleGenericError(res, error, 'SEEK_FAILED', 'Seek failed');
    }
  });

  router.post('/api/rooms/:roomId/playback/next', async (req: Request, res: Response) => {
    const { roomId } = req.params;
    try {
      const state = await streamingService.skip(roomId);
      res.json(state);
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      handleGenericError(res, error, 'NEXT_FAILED', 'Next failed');
    }
  });

  router.post('/api/playback/previous', (_req: Request, res: Response) => {
    res.status(501).json(createError('PREVIOUS_NOT_IMPLEMENTED', 'Previous track is not yet supported'));
  });

  return router;
}

function handlePlaybackError(res: Response, error: unknown): void {
  if (error instanceof QueueError) {
    res.status(503).json(createError('QUEUE_EMPTY', error.message));
    return;
  }
  if (error instanceof TrackNotFoundError) {
    res.status(404).json(createError('TRACK_NOT_FOUND', error.message));
    return;
  }
  if (error instanceof ProviderError) {
    res.status(503).json(createError('PROVIDER_ERROR', error.message));
    return;
  }
  if (error instanceof NetworkError) {
    res.status(503).json(createError('NETWORK_ERROR', error.message));
    return;
  }
  handleGenericError(res, error, 'PLAYBACK_FAILED', 'Playback failed');
}

function handleGenericError(res: Response, error: unknown, code: string, fallbackMessage: string): void {
  if (error instanceof StreamingError) {
    res.status(500).json(createError(code, error.message));
    return;
  }
  res.status(500).json(createError(code, fallbackMessage));
}
