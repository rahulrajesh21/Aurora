import { ProviderType } from './ProviderType';

export abstract class StreamingError extends Error {
  constructor(message: string, public cause?: unknown) {
    super(message);
    this.name = 'StreamingError';
  }
}

export class ProviderError extends StreamingError {
  constructor(public provider: ProviderType, errorMessage: string, cause?: unknown) {
    super(`Provider error from ${provider}: ${errorMessage}`, cause);
    this.name = 'ProviderError';
  }
}

export class AuthenticationError extends StreamingError {
  constructor(public provider: ProviderType, cause?: unknown) {
    super(`Authentication failed for provider ${provider}`, cause);
    this.name = 'AuthenticationError';
  }
}

export class NetworkError extends StreamingError {
  constructor(message: string, cause?: unknown) {
    super(`Network error: ${message}`, cause);
    this.name = 'NetworkError';
  }
}

export class TrackNotFoundError extends StreamingError {
  constructor(public trackId: string, public provider?: ProviderType) {
    super(`Track not found: ${trackId}${provider ? ` on ${provider}` : ''}`);
    this.name = 'TrackNotFoundError';
  }
}

export class InvalidSeekPosition extends StreamingError {
  constructor(public position: number, public trackDuration: number) {
    super(`Invalid seek position ${position} (track duration ${trackDuration})`);
    this.name = 'InvalidSeekPosition';
  }
}

export class QueueError extends StreamingError {
  constructor(message: string) {
    super(`Queue error: ${message}`);
    this.name = 'QueueError';
  }
}

export class RateLimitError extends StreamingError {
  constructor(public provider: ProviderType, public retryAfterSeconds: number) {
    super(`Rate limit exceeded for ${provider}, retry after ${retryAfterSeconds}s`);
    this.name = 'RateLimitError';
  }
}
