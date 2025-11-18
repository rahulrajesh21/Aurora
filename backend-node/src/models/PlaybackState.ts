import { AudioFormat } from './StreamInfo';
import { Track } from './Track';

export interface PlaybackState {
  currentTrack: Track | null;
  positionSeconds: number;
  isPlaying: boolean;
  queue: Track[];
  shuffleEnabled: boolean;
  timestamp: number;
  streamUrl?: string | null;
  streamFormat?: AudioFormat | null;
}

export function createEmptyState(): PlaybackState {
  return {
    currentTrack: null,
    positionSeconds: 0,
    isPlaying: false,
    queue: [],
    shuffleEnabled: false,
    timestamp: Date.now(),
    streamUrl: null,
    streamFormat: null,
  };
}
