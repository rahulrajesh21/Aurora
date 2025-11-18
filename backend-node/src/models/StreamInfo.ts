import { Track } from './Track';

export enum AudioFormat {
  MP3 = 'MP3',
  AAC = 'AAC',
  WEBM = 'WEBM',
  OGG = 'OGG',
}

export interface StreamInfo {
  streamUrl: string;
  track: Track;
  expiresAt?: number;
  format: AudioFormat;
}
