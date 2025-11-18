import { QueueItem } from '../models/QueueItem';
import { Track } from '../models/Track';

export interface QueueStorage {
  addToQueue(track: Track, addedBy: string): Promise<QueueItem>;
  removeFromQueue(queueItemId: string): Promise<boolean>;
  getQueue(): Promise<QueueItem[]>;
  getNext(): Promise<QueueItem | null>;
  popNext(): Promise<QueueItem | null>;
  clearQueue(): Promise<void>;
  reorderQueue(queueItemId: string, newPosition: number): Promise<boolean>;
}
