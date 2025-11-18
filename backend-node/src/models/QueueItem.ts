import { Track } from './Track';

export interface QueueItem {
  id: string;
  track: Track;
  addedBy: string;
  addedAt: number;
  position: number;
}
