import { ProviderType } from '../models/ProviderType';
import { StreamInfo } from '../models/StreamInfo';
import { Track } from '../models/Track';

export interface MusicProvider {
  providerType: ProviderType;
  search(query: string, limit?: number): Promise<Track[]>;
  getTrack(trackId: string): Promise<Track | null>;
  getStreamUrl(trackId: string): Promise<StreamInfo>;
  isAvailable(): Promise<boolean>;
}
