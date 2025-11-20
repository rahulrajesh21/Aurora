import { Server } from 'node:http';

import { WebSocketServer } from 'ws';
import type { WebSocket } from 'ws';
import { v4 as uuid } from 'uuid';

import { ServiceContainer } from '../container/ServiceContainer';
import { logger } from '../utils/logger';

export function registerWebSocketRoutes(server: Server, container: ServiceContainer): void {
  const webSocketManager = container.getWebSocketManager();
  const streamingService = container.getStreamingService();

  const wss = new WebSocketServer({ server, path: '/api/playback/stream' });

  wss.on('connection', async (socket: WebSocket, req) => {
    const sessionId = uuid();
    const url = new URL(req.url ?? '', `http://${req.headers.host}`);
    const roomId = url.searchParams.get('roomId');

    if (!roomId) {
      logger.error({ sessionId }, 'WebSocket connection missing roomId');
      socket.close(1008, 'roomId required');
      return;
    }

    logger.info({ sessionId, roomId }, 'WebSocket client connected');
    webSocketManager.addConnection(roomId, sessionId, socket);

    try {
      const state = await streamingService.getState(roomId);
      socket.send(JSON.stringify(state));
    } catch (error) {
      logger.error({ error, sessionId, roomId }, 'Failed to send initial state');
    }

    socket.on('message', (message: Buffer) => {
      const payload = message.toString();
      if (payload === 'ping') {
        socket.send('pong');
      }
    });

    socket.on('close', () => {
      webSocketManager.removeConnection(roomId, sessionId);
      logger.info({ sessionId, roomId }, 'WebSocket client disconnected');
    });

    socket.on('error', (error: Error) => {
      logger.error({ error, sessionId, roomId }, 'WebSocket error');
      webSocketManager.removeConnection(roomId, sessionId);
    });
  });
}
