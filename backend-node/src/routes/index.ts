import type { Express } from 'express';

import { StreamingService } from '../services/StreamingService';
import { createPlaybackRoutes } from './playbackRoutes';
import { createQueueRoutes } from './queueRoutes';
import { createSearchRoutes } from './searchRoutes';
import { RoomManager } from '../services/RoomManager';
import { createRoomRoutes } from './roomRoutes';

export function registerHttpRoutes(app: Express, streamingService: StreamingService, roomManager?: RoomManager): void {
  app.use(createSearchRoutes(streamingService));
  app.use(createPlaybackRoutes(streamingService));
  app.use(createQueueRoutes(streamingService));
  if (roomManager) {
    app.use(createRoomRoutes(roomManager));
  }
}
