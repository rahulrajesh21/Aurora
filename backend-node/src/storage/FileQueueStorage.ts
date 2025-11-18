import fs from 'node:fs';
import path from 'node:path';

import { Mutex } from 'async-mutex';
import { v4 as uuid } from 'uuid';

import { QueueItem } from '../models/QueueItem';
import { Track } from '../models/Track';
import { QueueStorage } from './QueueStorage';

export class FileQueueStorage implements QueueStorage {
  private readonly mutex = new Mutex();
  private readonly queue: QueueItem[] = [];
  private readonly storagePath: string;

  constructor(storagePath: string) {
    this.storagePath = path.resolve(storagePath);
    this.bootstrap();
  }

  private bootstrap(): void {
    if (!fs.existsSync(this.storagePath)) {
      return;
    }
    try {
      const content = fs.readFileSync(this.storagePath, 'utf-8');
      if (content.trim()) {
        const items = JSON.parse(content) as QueueItem[];
        this.queue.push(...items);
      }
    } catch (error) {
      console.error('Failed to initialize queue storage', error);
    }
  }

  async addToQueue(track: Track, addedBy: string): Promise<QueueItem> {
    return this.mutex.runExclusive(async () => {
      const queueItem: QueueItem = {
        id: uuid(),
        track,
        addedBy,
        addedAt: Date.now(),
        position: this.queue.length,
      };
      this.queue.push(queueItem);
      await this.persist();
      return queueItem;
    });
  }

  async removeFromQueue(queueItemId: string): Promise<boolean> {
    return this.mutex.runExclusive(async () => {
      const index = this.queue.findIndex((item) => item.id === queueItemId);
      if (index === -1) {
        return false;
      }
      this.queue.splice(index, 1);
      this.reassignPositions();
      await this.persist();
      return true;
    });
  }

  async getQueue(): Promise<QueueItem[]> {
    return this.mutex.runExclusive(async () => [...this.queue]);
  }

  async getNext(): Promise<QueueItem | null> {
    return this.mutex.runExclusive(async () => this.queue[0] ?? null);
  }

  async popNext(): Promise<QueueItem | null> {
    return this.mutex.runExclusive(async () => {
      if (!this.queue.length) {
        return null;
      }
      const item = this.queue.shift() ?? null;
      this.reassignPositions();
      await this.persist();
      return item;
    });
  }

  async clearQueue(): Promise<void> {
    return this.mutex.runExclusive(async () => {
      this.queue.length = 0;
      await this.persist();
    });
  }

  async reorderQueue(queueItemId: string, newPosition: number): Promise<boolean> {
    return this.mutex.runExclusive(async () => {
      if (newPosition < 0 || newPosition >= this.queue.length) {
        return false;
      }
      const currentIndex = this.queue.findIndex((item) => item.id === queueItemId);
      if (currentIndex === -1) {
        return false;
      }
      const [item] = this.queue.splice(currentIndex, 1);
      this.queue.splice(newPosition, 0, item);
      this.reassignPositions();
      await this.persist();
      return true;
    });
  }

  private reassignPositions(): void {
    this.queue.forEach((item, index) => {
      item.position = index;
    });
  }

  private async persist(): Promise<void> {
    try {
      await fs.promises.mkdir(path.dirname(this.storagePath), { recursive: true });
      await fs.promises.writeFile(this.storagePath, JSON.stringify(this.queue, null, 2), 'utf-8');
    } catch (error) {
      console.error('Failed to persist queue storage', error);
    }
  }
}
