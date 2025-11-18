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

  wss.on('connection', async (socket: WebSocket) => {
    const sessionId = uuid();
    logger.info({ sessionId }, 'WebSocket client connected');
    webSocketManager.addConnection(sessionId, socket);

    try {
      const state = await streamingService.getState();
      socket.send(JSON.stringify(state));
    } catch (error) {
      logger.error({ error, sessionId }, 'Failed to send initial state');
    }

    socket.on('message', (message: Buffer) => {
      const payload = message.toString();
      if (payload === 'ping') {
        socket.send('pong');
      }
    });

    socket.on('close', () => {
      webSocketManager.removeConnection(sessionId);
      logger.info({ sessionId }, 'WebSocket client disconnected');
    });

  socket.on('error', (error: Error) => {
      logger.error({ error, sessionId }, 'WebSocket error');
      webSocketManager.removeConnection(sessionId);
    });
  });
}
