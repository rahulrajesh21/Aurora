import { spawn, ChildProcess } from 'node:child_process';
import { logger } from '../utils/logger';
import { AppConfig } from '../config/appConfig';

export class PotProviderService {
    private process: ChildProcess | null = null;
    private readonly port: number;
    private readonly enabled: boolean;
    private isRunning: boolean = false;

    constructor(config: AppConfig['youtube']['ytdlp']) {
        this.enabled = config?.potProvider?.enabled ?? false;
        this.port = config?.potProvider?.port ?? 4416;
    }

    async start(): Promise<void> {
        if (!this.enabled) {
            logger.info('POT provider service is disabled');
            return;
        }

        if (this.isRunning) {
            logger.warn('POT provider service is already running');
            return;
        }

        try {
            logger.info({ port: this.port }, 'Starting bgutil-pot server...');

            // Spawn the bgutil-pot server (Node.js version)
            // Installed globally via npm
            this.process = spawn('bgutil-ytdlp-pot-provider', ['server', '--port', this.port.toString()], {
                stdio: ['ignore', 'pipe', 'pipe'],
            });

            this.process.stdout?.on('data', (data) => {
                logger.debug({ output: data.toString().trim() }, 'bgutil-pot stdout');
            });

            this.process.stderr?.on('data', (data) => {
                logger.warn({ output: data.toString().trim() }, 'bgutil-pot stderr');
            });

            this.process.on('error', (error) => {
                logger.error({ error }, 'bgutil-pot process error');
                this.isRunning = false;
            });

            this.process.on('close', (code) => {
                logger.warn({ code }, 'bgutil-pot process exited');
                this.isRunning = false;
                this.process = null;
            });

            this.isRunning = true;
            logger.info('bgutil-pot server started successfully');

            // Wait a moment for the server to initialize
            await new Promise((resolve) => setTimeout(resolve, 1000));
        } catch (error) {
            logger.error({ error }, 'Failed to start bgutil-pot server');
            this.isRunning = false;
            throw error;
        }
    }

    async stop(): Promise<void> {
        if (this.process) {
            logger.info('Stopping bgutil-pot server...');
            this.process.kill();
            this.process = null;
            this.isRunning = false;
        }
    }

    isHealthy(): boolean {
        return this.isRunning;
    }

    getPort(): number {
        return this.port;
    }
}
