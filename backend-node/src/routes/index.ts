import type { Express } from 'express';

import { StreamingService } from '../services/StreamingService';
import { createPlaybackRoutes } from './playbackRoutes';
import { createQueueRoutes } from './queueRoutes';
import { createSearchRoutes } from './searchRoutes';
import { RoomManager } from '../services/RoomManager';
import { createRoomRoutes } from './roomRoutes';
import { LyricsService } from '../services/lyrics/LyricsService';
import { createLyricsRoutes } from './lyricsRoutes';

export function registerHttpRoutes(
  app: Express,
  streamingService: StreamingService,
  roomManager?: RoomManager,
  lyricsService?: LyricsService,
): void {
  app.use(createSearchRoutes(streamingService));
  app.use(createPlaybackRoutes(streamingService));
  app.use(createQueueRoutes(streamingService));
  if (roomManager) {
    app.use(createRoomRoutes(roomManager));
  }
  if (lyricsService) {
    app.use(createLyricsRoutes(lyricsService));
  }
}
