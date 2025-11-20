import type { WebSocket } from 'ws';

import { PlaybackState } from '../models/PlaybackState';

export class WebSocketManager {
  // Map<roomId, Map<connectionId, WebSocket>>
  private readonly rooms = new Map<string, Map<string, WebSocket>>();

  addConnection(roomId: string, connectionId: string, socket: WebSocket): void {
    let roomConnections = this.rooms.get(roomId);
    if (!roomConnections) {
      roomConnections = new Map();
      this.rooms.set(roomId, roomConnections);
    }
    roomConnections.set(connectionId, socket);
  }

  removeConnection(roomId: string, connectionId: string): void {
    const roomConnections = this.rooms.get(roomId);
    if (roomConnections) {
      const socket = roomConnections.get(connectionId);
      socket?.close();
      roomConnections.delete(connectionId);
      if (roomConnections.size === 0) {
        this.rooms.delete(roomId);
      }
    }
  }

  async broadcastState(roomId: string, state: PlaybackState): Promise<void> {
    const roomConnections = this.rooms.get(roomId);
    if (!roomConnections || !roomConnections.size) {
      return;
    }
    const payload = JSON.stringify(state);
    const disconnected: string[] = [];
    for (const [id, socket] of roomConnections) {
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
    disconnected.forEach((id) => roomConnections.delete(id));
  }

  getConnectionCount(roomId?: string): number {
    if (roomId) {
      return this.rooms.get(roomId)?.size ?? 0;
    }
    let total = 0;
    for (const room of this.rooms.values()) {
      total += room.size;
    }
    return total;
  }
}
