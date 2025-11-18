import { Request, Response, Router } from 'express';
import { spawn } from 'node:child_process';

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
import { logger } from '../utils/logger';

interface PlayRequest {
  trackId?: string;
  provider?: ProviderType;
}

interface SeekRequest {
  positionSeconds?: number;
  percentage?: number;
}

export function createPlaybackRoutes(streamingService: StreamingService): Router {
  const router = Router();

  router.post('/api/playback/play', async (req: Request, res: Response) => {
    const body = req.body as PlayRequest | undefined;
    try {
      if (body?.trackId && body.provider) {
        const state = await streamingService.play(body.trackId, body.provider);
        res.json(state);
        return;
      }
      const state = await streamingService.resume();
      res.json(state);
    } catch (error) {
      handlePlaybackError(res, error);
    }
  });

  router.post('/api/playback/pause', async (_req: Request, res: Response) => {
    try {
      const state = await streamingService.pause();
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'PAUSE_FAILED', 'Pause failed');
    }
  });

  router.post('/api/playback/resume', async (_req: Request, res: Response) => {
    try {
      const state = await streamingService.resume();
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'RESUME_FAILED', 'Resume failed');
    }
  });

  router.post('/api/playback/skip', async (_req: Request, res: Response) => {
    try {
      const state = await streamingService.skip();
      res.json(state);
    } catch (error) {
      if (error instanceof QueueError) {
        res.status(400).json(createError('QUEUE_ERROR', error.message));
        return;
      }
      handleGenericError(res, error, 'SKIP_FAILED', 'Skip failed');
    }
  });

  router.get('/api/playback/state', async (_req: Request, res: Response) => {
    try {
      const state = await streamingService.getState();
      res.json(state);
    } catch (error) {
      handleGenericError(res, error, 'STATE_FAILED', 'Failed to fetch playback state');
    }
  });

  router.get('/api/playback/stream/:trackId', async (req: Request, res: Response) => {
    const { trackId } = req.params;
    if (!trackId) {
      res.status(400).json(createError('INVALID_REQUEST', 'Track ID is required'));
      return;
    }

    logger.info({ trackId }, 'Fetching stream URL');
    const process = spawn('yt-dlp', ['-f', 'bestaudio', '--get-url', '--no-playlist', `https://www.youtube.com/watch?v=${trackId}`]);

    let stdout = '';
    let stderr = '';

    process.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString();
    });

    process.stderr.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
    });

    process.on('close', (code: number | null) => {
      if (code !== 0 || !stdout.trim()) {
        logger.error({ stderr }, 'Failed to extract stream URL');
        res.status(503).json(createError('STREAM_ERROR', 'Failed to get stream URL'));
        return;
      }
      res.redirect(stdout.trim());
    });

    process.on('error', (error: Error) => {
      logger.error({ error }, 'yt-dlp spawn error');
      res.status(500).json(createError('STREAM_ERROR', 'Failed to get stream URL'));
    });
  });

  router.post('/api/playback/seek', async (req: Request, res: Response) => {
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
        ? await streamingService.seek(body.positionSeconds)
        : await streamingService.seekByPercentage(body.percentage!);
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

  router.post('/api/playback/next', async (_req: Request, res: Response) => {
    try {
      const state = await streamingService.skip();
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
