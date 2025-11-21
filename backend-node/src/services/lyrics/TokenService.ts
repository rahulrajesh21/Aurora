import puppeteer from 'puppeteer-extra';
import StealthPlugin from 'puppeteer-extra-plugin-stealth';
import { Browser, Page } from 'puppeteer';
import { logger } from '../../utils/logger';

puppeteer.use(StealthPlugin());

const CUBEY_CHALLENGE_URL = "https://lyrics.api.dacubeking.com/challenge";
const CUBEY_LYRICS_API_URL = "https://lyrics.api.dacubeking.com/";

export class TokenService {
    private static instance: TokenService;
    private browser: Browser | null = null;
    private page: Page | null = null;
    private cachedJwt: string | null = null;
    private tokenPromise: Promise<string> | null = null;

    private constructor() { }

    public static getInstance(): TokenService {
        if (!TokenService.instance) {
            TokenService.instance = new TokenService();
        }
        return TokenService.instance;
    }

    public async getToken(forceRefresh = false): Promise<string> {
        if (this.cachedJwt && !forceRefresh && !this.isJwtExpired(this.cachedJwt)) {
            return this.cachedJwt;
        }

        if (this.tokenPromise) {
            return this.tokenPromise;
        }

        this.tokenPromise = this.fetchNewJwt();
        try {
            const jwt = await this.tokenPromise;
            this.cachedJwt = jwt;
            return jwt;
        } finally {
            this.tokenPromise = null;
        }
    }

    private isJwtExpired(token: string): boolean {
        try {
            const payloadBase64Url = token.split(".")[1];
            if (!payloadBase64Url) return true;
            const payloadBase64 = payloadBase64Url.replace(/-/g, "+").replace(/_/g, "/");
            const decodedPayload = atob(payloadBase64);
            const payload = JSON.parse(decodedPayload);
            const expirationTimeInSeconds = payload.exp;
            if (!expirationTimeInSeconds) return true;
            const nowInSeconds = Date.now() / 1000;
            return nowInSeconds > expirationTimeInSeconds;
        } catch (e) {
            logger.error({ error: e }, 'Error checking JWT expiration');
            return true;
        }
    }

    private async fetchNewJwt(): Promise<string> {
        logger.info('Starting Turnstile challenge for new JWT...');

        try {
            if (!this.browser) {
                this.browser = await puppeteer.launch({
                    headless: true,
                    args: [
                        '--no-sandbox',
                        '--disable-setuid-sandbox',
                        '--disable-blink-features=AutomationControlled',
                        '--window-size=1920,1080'
                    ]
                });
            }

            if (!this.browser) {
                throw new Error('Failed to launch browser');
            }

            if (!this.page) {
                this.page = await this.browser.newPage();
                await this.page.setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36');
                await this.page.setViewport({ width: 1920, height: 1080 });
            }

            // Navigate to the API domain itself to potentially help with origin checks
            await this.page.goto('https://lyrics.api.dacubeking.com/', { waitUntil: 'domcontentloaded' });

            // Inject iframe and capture token
            const turnstileToken = await this.page.evaluate((challengeUrl) => {
                return new Promise<string>((resolve, reject) => {
                    const iframe = document.createElement("iframe");
                    iframe.src = challengeUrl;
                    // IMPORTANT: Do NOT use display: none, as it blocks Turnstile execution.
                    // Use visibility: hidden or 0x0 size as per better-lyrics implementation.
                    iframe.style.position = "fixed";
                    iframe.style.width = "0px";
                    iframe.style.height = "0px";
                    iframe.style.border = "none";
                    iframe.style.zIndex = "999999";
                    iframe.style.visibility = "hidden"; // specific to be safe, though 0x0 is usually enough

                    document.body.appendChild(iframe);

                    const messageListener = (event: MessageEvent) => {
                        if (event.data && event.data.type === 'turnstile-token') {
                            window.removeEventListener("message", messageListener);
                            resolve(event.data.token);
                        } else if (event.data && event.data.type === 'turnstile-error') {
                            window.removeEventListener("message", messageListener);
                            reject(new Error(event.data.error));
                        }
                    };

                    window.addEventListener("message", messageListener);

                    setTimeout(() => {
                        window.removeEventListener("message", messageListener);
                        reject(new Error("Timeout waiting for token"));
                    }, 60000); // Increased timeout
                });
            }, CUBEY_CHALLENGE_URL);

            logger.info('Obtained Turnstile token, exchanging for JWT...');

            // Exchange for JWT
            const response = await fetch(CUBEY_LYRICS_API_URL + "verify-turnstile", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token: turnstileToken }),
            });

            if (!response.ok) {
                throw new Error(`JWT exchange failed: ${response.statusText}`);
            }

            const data = await response.json() as { jwt: string };
            if (!data.jwt) {
                throw new Error("No JWT returned from API");
            }

            logger.info('Successfully obtained JWT');

            await this.cleanup();

            return data.jwt;

        } catch (error: any) {
            logger.error({ error: error.message, stack: error.stack }, 'Failed to fetch JWT');
            if (this.page) {
                try {
                    await this.page.screenshot({ path: 'turnstile-error.png', fullPage: true });
                    logger.info('Saved screenshot to turnstile-error.png');
                } catch (e) {
                    logger.error('Failed to save screenshot');
                }
            }
            await this.cleanup();
            throw error;
        }
    }

    private async cleanup() {
        if (this.page) {
            await this.page.close().catch(() => { });
            this.page = null;
        }
        if (this.browser) {
            await this.browser.close().catch(() => { });
            this.browser = null;
        }
    }
}
