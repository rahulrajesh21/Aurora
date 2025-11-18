import { ProviderType } from './ProviderType';
import { Track } from './Track';

export interface SearchResult {
  tracks: Track[];
  query: string;
  providers: ProviderType[];
}
