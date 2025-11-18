import fs from 'node:fs/promises';
import path from 'node:path';

import { Mutex } from 'async-mutex';

import { Room, RoomInvite, RoomMember } from '../models/Room';
import { RoomStorage } from './RoomStorage';

export class FileRoomStorage implements RoomStorage {
  private readonly mutex = new Mutex();
  private readonly basePath: string;

  constructor(storagePath: string) {
    this.basePath = path.resolve(storagePath);
  }

  async listRooms(): Promise<Room[]> {
    return this.mutex.runExclusive(async () => {
      await this.ensureBasePath();
      const rooms: Room[] = [];
      const entries = await fs.readdir(this.basePath, { withFileTypes: true }).catch(() => []);
      for (const entry of entries) {
        if (!entry.isDirectory()) {
          continue;
        }
        const room = await this.readRoom(entry.name);
        if (room) {
          rooms.push(room);
        }
      }
      return rooms;
    });
  }

  async getRoom(roomId: string): Promise<Room | null> {
    return this.mutex.runExclusive(async () => this.readRoom(roomId));
  }

  async saveRoom(room: Room): Promise<void> {
    return this.mutex.runExclusive(async () => {
      const dir = await this.ensureRoomDir(room.id);
      const roomPath = path.join(dir, 'room.json');
      await fs.writeFile(roomPath, JSON.stringify(room, null, 2), 'utf-8');
    });
  }

  async deleteRoom(roomId: string): Promise<void> {
    return this.mutex.runExclusive(async () => {
      const dir = path.join(this.basePath, roomId);
      await fs.rm(dir, { recursive: true, force: true }).catch(() => undefined);
    });
  }

  async getMembers(roomId: string): Promise<RoomMember[]> {
    return this.mutex.runExclusive(async () => this.readCollection<RoomMember>(roomId, 'members.json'));
  }

  async saveMembers(roomId: string, members: RoomMember[]): Promise<void> {
    return this.mutex.runExclusive(async () => this.writeCollection(roomId, 'members.json', members));
  }

  async getInvites(roomId: string): Promise<RoomInvite[]> {
    return this.mutex.runExclusive(async () => this.readCollection<RoomInvite>(roomId, 'invites.json'));
  }

  async saveInvites(roomId: string, invites: RoomInvite[]): Promise<void> {
    return this.mutex.runExclusive(async () => this.writeCollection(roomId, 'invites.json', invites));
  }

  private async readRoom(roomId: string): Promise<Room | null> {
    const file = path.join(this.basePath, roomId, 'room.json');
    try {
      const contents = await fs.readFile(file, 'utf-8');
      return JSON.parse(contents) as Room;
    } catch (error) {
      return null;
    }
  }

  private async readCollection<T>(roomId: string, filename: string): Promise<T[]> {
    const file = path.join(this.basePath, roomId, filename);
    try {
      const contents = await fs.readFile(file, 'utf-8');
      const parsed = JSON.parse(contents) as T[];
      return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
      return [];
    }
  }

  private async writeCollection(roomId: string, filename: string, data: unknown[]): Promise<void> {
    const dir = await this.ensureRoomDir(roomId);
    await fs.writeFile(path.join(dir, filename), JSON.stringify(data, null, 2), 'utf-8');
  }

  private async ensureBasePath(): Promise<void> {
    await fs.mkdir(this.basePath, { recursive: true });
  }

  private async ensureRoomDir(roomId: string): Promise<string> {
    await this.ensureBasePath();
    const dir = path.join(this.basePath, roomId);
    await fs.mkdir(dir, { recursive: true });
    return dir;
  }
}
