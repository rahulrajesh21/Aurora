import http from 'node:http';

import cors from 'cors';
import dotenv from 'dotenv';
import express, { type Request, type Response } from 'express';

import { ServiceContainer } from './container/ServiceContainer';
import { registerHttpRoutes } from './routes';
import { registerWebSocketRoutes } from './routes/webSocketRoutes';
import { logger } from './utils/logger';

dotenv.config();

async function bootstrap(): Promise<void> {
  try {
    const app = express();
    app.use(cors());
    app.use(express.json());

    const container = await ServiceContainer.initialize();
    const streamingService = container.getStreamingService();
    const roomManager = container.getRoomManager();
    const lyricsService = container.getLyricsService();
    const popularAlbumsService = container.getPopularAlbumsService();
    const config = container.getConfig();

    logger.info(
      {
        youtubeConfigured: Boolean(config.youtube.apiKey),
        queueMaxSize: config.queue.maxSize,
        queuePersistence: config.queue.persistenceEnabled,
        playbackRetryAttempts: config.playback.retryAttempts,
        statePersistence: config.state.persistenceEnabled,
      },
      'Configuration loaded successfully',
    );

    app.get('/health', (_req: Request, res: Response) => {
      res.json({ status: 'ok' });
    });

    registerHttpRoutes(app, streamingService, roomManager, lyricsService, popularAlbumsService);

    const server = http.createServer(app);
    registerWebSocketRoutes(server, container);

    const port = Number(process.env.PORT ?? 8080);
    server.listen(port, '0.0.0.0', () => {
      logger.info(`Aurora Node backend running on port ${port}`);
    });
  } catch (error) {
    logger.error({ error }, 'Failed to start server');
    process.exit(1);
  }
}

void bootstrap();

process.on('unhandledRejection', (reason: unknown) => {
  logger.error({ reason }, 'Unhandled promise rejection');
});

process.on('SIGTERM', () => {
  logger.info('Received SIGTERM, shutting down');
  process.exit(0);
});
