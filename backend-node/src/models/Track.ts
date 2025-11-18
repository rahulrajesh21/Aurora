import { ProviderType } from './ProviderType';

export interface Track {
  id: string;
  title: string;
  artist: string;
  durationSeconds: number;
  provider: ProviderType;
  thumbnailUrl?: string;
  externalUrl?: string;
}

export function assertTrack(track: Track): void {
  if (!track.id || !track.title || !track.artist) {
    throw new Error('Invalid track payload');
  }
  if (track.durationSeconds < 0) {
    throw new Error('Track duration must be non-negative');
  }
}
