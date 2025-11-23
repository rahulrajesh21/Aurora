import { Request, Response } from 'express';
import https from 'https';
import { IncomingMessage } from 'http';
import { logger } from '../utils/logger';
import { StreamingService } from '../services/StreamingService';
import { createError } from '../routes/types';

export class StreamProxyController {
    constructor(private streamingService: StreamingService) { }

    public streamAudio = async (req: Request, res: Response): Promise<void> => {
        const { trackId } = req.params;

        if (!trackId) {
            res.status(400).json(createError('INVALID_REQUEST', 'Track ID is required'));
            return;
        }

        try {
            // 1. Get the actual YouTube stream URL
            const streamUrl = await this.streamingService.resolveStreamUrl(trackId);

            // 2. Prepare headers for the upstream request (to YouTube)
            const headers: Record<string, string> = {};
            if (req.headers.range) {
                headers['Range'] = req.headers.range;
            }

            // 3. Make the request to YouTube
            const proxyReq = https.get(streamUrl, { headers }, (proxyRes: IncomingMessage) => {
                // 4. Forward the status code
                res.status(proxyRes.statusCode || 200);

                // 5. Forward relevant headers
                const forwardHeaders = [
                    'content-type',
                    'content-length',
                    'accept-ranges',
                    'content-range',
                    'cache-control',
                ];

                forwardHeaders.forEach((headerName) => {
                    const value = proxyRes.headers[headerName];
                    if (value) {
                        res.setHeader(headerName, value);
                    }
                });

                // 6. Pipe the data
                proxyRes.pipe(res);

                proxyRes.on('error', (err) => {
                    logger.error({ err, trackId }, 'Error in upstream stream response');
                    if (!res.headersSent) {
                        res.status(502).json(createError('UPSTREAM_ERROR', 'Error receiving stream from YouTube'));
                    } else {
                        res.end();
                    }
                });
            });

            proxyReq.on('error', (err) => {
                logger.error({ err, trackId }, 'Error making upstream request to YouTube');
                if (!res.headersSent) {
                    res.status(502).json(createError('UPSTREAM_ERROR', 'Failed to connect to YouTube stream'));
                }
            });

            // Handle client disconnect
            req.on('close', () => {
                proxyReq.destroy();
            });

        } catch (error) {
            logger.error({ error, trackId }, 'Failed to resolve or proxy stream');
            if (!res.headersSent) {
                res.status(503).json(createError('STREAM_ERROR', 'Failed to prepare stream'));
            }
        }
    };
}
