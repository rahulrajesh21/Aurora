import { randomInt } from 'node:crypto';

import { Mutex } from 'async-mutex';
import { v4 as uuid } from 'uuid';

import { QueueItem } from '../models/QueueItem';
import { QueueError } from '../models/StreamingError';
import { Track } from '../models/Track';
import { QueueStorage } from '../storage/QueueStorage';

export class QueueManager {
  private readonly mutex = new Mutex();
  private queue: QueueItem[] = [];
  private shuffleEnabled = false;
  private originalOrder: QueueItem[] | null = null;
  private currentlyPlayingId: string | null = null;

  constructor(private readonly storage?: QueueStorage, private readonly maxQueueSize = 100) {}

  async init(): Promise<void> {
    if (!this.storage) {
      return;
    }
    this.queue = await this.storage.getQueue();
    if (this.queue.length) {
      this.queue = this.queue.map((item, index) => ({ ...item, position: index }));
    }
  }

  async addTrack(track: Track, addedBy = 'system'): Promise<QueueItem> {
    return this.mutex.runExclusive(async () => {
      if (this.queue.length >= this.maxQueueSize) {
        throw new QueueError(`Queue is full. Max size ${this.maxQueueSize}`);
      }
      const queueItem: QueueItem = this.storage
        ? await this.storage.addToQueue(track, addedBy)
        : {
            id: uuid(),
            track,
            addedBy,
            addedAt: Date.now(),
            position: this.queue.length,
          };
      this.queue.push(queueItem);
      this.reassignPositions();
      return queueItem;
    });
  }

  async removeTrack(position: number): Promise<void> {
    return this.mutex.runExclusive(async () => {
      if (position < 0 || position >= this.queue.length) {
        throw new QueueError(`Invalid position ${position}. Queue size ${this.queue.length}`);
      }
      const [removed] = this.queue.splice(position, 1);
      this.reassignPositions();
      if (removed && removed.id === this.currentlyPlayingId) {
        this.currentlyPlayingId = null;
      }
      if (removed) {
        await this.storage?.removeFromQueue(removed.id);
      }
    });
  }

  async reorderTrack(fromPosition: number, toPosition: number): Promise<void> {
    return this.mutex.runExclusive(async () => {
      if (fromPosition < 0 || fromPosition >= this.queue.length) {
        throw new QueueError(`Invalid from position ${fromPosition}`);
      }
      if (toPosition < 0 || toPosition >= this.queue.length) {
        throw new QueueError(`Invalid to position ${toPosition}`);
      }
      if (fromPosition === toPosition) {
        return;
      }
      const [item] = this.queue.splice(fromPosition, 1);
      this.queue.splice(toPosition, 0, item);
      this.reassignPositions();
      if (item) {
        await this.storage?.reorderQueue(item.id, toPosition);
      }
    });
  }

  async clearQueue(): Promise<void> {
    return this.mutex.runExclusive(async () => {
      this.queue = [];
      this.originalOrder = null;
      this.shuffleEnabled = false;
       this.currentlyPlayingId = null;
      await this.storage?.clearQueue();
    });
  }

  async shuffle(): Promise<void> {
    return this.mutex.runExclusive(async () => {
      this.shuffleUnsafe();
    });
  }

  async toggleShuffle(): Promise<void> {
    return this.mutex.runExclusive(async () => {
      if (this.shuffleEnabled && this.originalOrder) {
        this.queue = [...this.originalOrder];
        this.originalOrder = null;
        this.shuffleEnabled = false;
        this.reassignPositions();
        return;
      }
      this.shuffleUnsafe();
    });
  }

  private shuffleUnsafe(): void {
    if (!this.queue.length) {
      return;
    }
    if (!this.shuffleEnabled) {
      this.originalOrder = [...this.queue];
    }
    const currentTrack = this.currentlyPlayingId ? this.queue.find((item) => item.id === this.currentlyPlayingId) : undefined;
    const rest = currentTrack ? this.queue.filter((item) => item.id !== currentTrack.id) : [...this.queue];
    for (let i = rest.length - 1; i > 0; i -= 1) {
      const j = randomInt(i + 1);
      [rest[i], rest[j]] = [rest[j], rest[i]];
    }
    this.queue = currentTrack ? [currentTrack, ...rest] : rest;
    this.reassignPositions();
    this.shuffleEnabled = true;
  }

  setCurrentlyPlaying(trackId: string | null): void {
    this.currentlyPlayingId = trackId;
  }

  getQueueSnapshot(): Track[] {
    return this.queue.map((item) => item.track);
  }

  getQueueItems(): QueueItem[] {
    return [...this.queue];
  }

  getQueueSize(): number {
    return this.queue.length;
  }

  isShuffleEnabled(): boolean {
    return this.shuffleEnabled;
  }

  async getNextTrack(): Promise<QueueItem | null> {
    return this.queue[0] ?? null;
  }

  async popNextTrack(): Promise<QueueItem | null> {
    return this.mutex.runExclusive(async () => {
      if (!this.queue.length) {
        return null;
      }
      const next = this.queue.shift() ?? null;
      this.reassignPositions();
      if (next) {
        await this.storage?.removeFromQueue(next.id);
      }
      return next;
    });
  }

  private reassignPositions(): void {
    this.queue = this.queue.map((item, index) => ({ ...item, position: index }));
  }
}
