import type { WebSocket } from 'ws';

import { PlaybackState } from '../models/PlaybackState';

export class WebSocketManager {
  private readonly connections = new Map<string, WebSocket>();

  addConnection(id: string, socket: WebSocket): void {
    this.connections.set(id, socket);
  }

  removeConnection(id: string): void {
    const socket = this.connections.get(id);
    socket?.close();
    this.connections.delete(id);
  }

  async broadcastState(state: PlaybackState): Promise<void> {
    if (!this.connections.size) {
      return;
    }
    const payload = JSON.stringify(state);
    const disconnected: string[] = [];
    for (const [id, socket] of this.connections) {
      if (socket.readyState !== socket.OPEN) {
        disconnected.push(id);
        continue;
      }
      try {
        socket.send(payload);
      } catch (error) {
        console.error('Failed to broadcast state', error);
        disconnected.push(id);
      }
    }
    disconnected.forEach((id) => this.connections.delete(id));
  }

  getConnectionCount(): number {
    return this.connections.size;
  }
}
