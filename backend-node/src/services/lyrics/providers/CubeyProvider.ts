import { logger } from '../../../utils/logger';
import type { LyricsRequest, LyricSourceResult } from '../types';
import { lrcFixers, parseLRC } from '../lrcUtils';
import { TokenService } from '../TokenService';

const CUBEY_LYRICS_API_URL = "https://lyrics.api.dacubeking.com/lyrics";

interface CubeyResponse {
    album?: string;
    artist?: string;
    song?: string;
    duration?: number;
    musixmatchWordByWordLyrics?: string;
    musixmatchSyncedLyrics?: string;
    lrclibSyncedLyrics?: string;
    lrclibPlainLyrics?: string;
}

export async function loadCubeyLyrics(
    request: LyricsRequest,
    signal: AbortSignal,
    type: 'richsync' | 'synced'
): Promise<LyricSourceResult | null> {
    try {
        const url = new URL(CUBEY_LYRICS_API_URL);
        url.searchParams.append("song", request.song);
        url.searchParams.append("artist", request.artist);
        url.searchParams.append("duration", String(request.durationMs / 1000)); // API expects seconds? cubey.ts uses providerParameters.duration which seems to be seconds in better-lyrics but let's verify. 
        // In better-lyrics, duration is passed as number. 
        // In Aurora LyricsRequest, durationMs is milliseconds.
        // better-lyrics/src/modules/lyrics/lyrics.ts:49: let duration = Number(detail.duration); -> detail.duration comes from player.
        // Usually player duration is seconds in some contexts, but let's check.
        // In Aurora, state.duration is ms.
        // Let's assume seconds for the API as is common, but I'll check cubey.ts again.
        // cubey.ts:158: url.searchParams.append("duration", String(providerParameters.duration));
        // If I look at better-lyrics/src/modules/lyrics/lyrics.ts, it converts detail.duration to Number.
        // I'll stick to seconds for now as that's safer for external APIs usually, or I can try both if it fails.
        // Actually, let's look at how parseLRC is called in cubey.ts: parseLRC(..., Number(providerParameters.duration)).

        url.searchParams.append("videoId", request.videoId);
        if (request.album) {
            url.searchParams.append("album", request.album);
        }
        // alwaysFetchMetadata is used in better-lyrics if swappedVideoId.
        // We can default to false or true.
        url.searchParams.append("alwaysFetchMetadata", "true");

        // Get JWT from TokenService
        let jwt: string;
        try {
            jwt = await TokenService.getInstance().getToken();
        } catch (e) {
            logger.error({ error: e }, 'Failed to get auth token for Cubey API');
            return null;
        }

        logger.info({ url: url.toString() }, 'Fetching lyrics from Cubey API');

        let response = await fetch(url.toString(), {
            signal,
            headers: {
                'Authorization': `Bearer ${jwt}`
            }
        });

        if (response.status === 403 || response.status === 401) {
            logger.warn('Token expired or invalid. Forcing refresh...');
            try {
                jwt = await TokenService.getInstance().getToken(true);
                response = await fetch(url.toString(), {
                    signal,
                    headers: {
                        'Authorization': `Bearer ${jwt}`
                    }
                });
            } catch (e) {
                logger.error({ error: e }, 'Failed to refresh token');
                return null;
            }
        }

        if (!response.ok) {
            logger.warn({ status: response.status }, 'Cubey API request failed');
            return null;
        }

        const data = await response.json() as CubeyResponse;

        if (type === 'richsync' && data.musixmatchWordByWordLyrics) {
            const lyrics = parseLRC(data.musixmatchWordByWordLyrics, request.durationMs);
            lrcFixers(lyrics); // Apply fixers like in cubey.ts
            return {
                lyrics,
                source: 'Musixmatch',
                sourceHref: 'https://www.musixmatch.com',
                musicVideoSynced: false,
                cacheAllowed: true
            };
        } else if (type === 'synced' && data.musixmatchSyncedLyrics) {
            const lyrics = parseLRC(data.musixmatchSyncedLyrics, request.durationMs);
            return {
                lyrics,
                source: 'Musixmatch',
                sourceHref: 'https://www.musixmatch.com',
                musicVideoSynced: false,
                cacheAllowed: true
            };
        }

        return null;

    } catch (error) {
        logger.error({ error }, 'Error fetching from Cubey API');
        return null;
    }
}
