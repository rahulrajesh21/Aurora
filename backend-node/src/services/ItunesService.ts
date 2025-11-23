import { logger } from '../utils/logger';

interface ItunesSearchResult {
    resultCount: number;
    results: ItunesResultItem[];
}

interface ItunesResultItem {
    artworkUrl100?: string;
    artworkUrl600?: string; // Sometimes available, but we usually scale up 100
    collectionName?: string;
    artistName?: string;
    trackName?: string;
}

const ITUNES_SEARCH_URL = 'https://itunes.apple.com/search';

export class ItunesService {
    constructor(private readonly fetchImpl: typeof fetch = fetch) { }

    async searchArtwork(artist: string, title: string): Promise<string | null> {
        try {
            const query = `${artist} ${title}`;
            const url = new URL(ITUNES_SEARCH_URL);
            url.searchParams.set('term', query);
            url.searchParams.set('media', 'music');
            url.searchParams.set('entity', 'song');
            url.searchParams.set('limit', '1');

            const response = await this.fetchImpl(url);
            if (!response.ok) {
                logger.warn({ status: response.status }, 'iTunes search failed');
                return null;
            }

            const data = (await response.json()) as ItunesSearchResult;
            if (data.resultCount === 0 || !data.results[0]) {
                return null;
            }

            const result = data.results[0];
            const artworkUrl = result.artworkUrl100;

            if (!artworkUrl) {
                return null;
            }

            // Upgrade quality to 600x600 or higher by modifying the URL
            // Example: .../100x100bb.jpg -> .../1000x1000bb.jpg
            return artworkUrl.replace('100x100', '1000x1000');
        } catch (error) {
            logger.warn({ error, artist, title }, 'Failed to fetch iTunes artwork');
            return null;
        }
    }
}
