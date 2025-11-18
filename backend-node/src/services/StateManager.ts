import fs from 'node:fs/promises';
import path from 'node:path';

import { Mutex } from 'async-mutex';

import { PlaybackState, createEmptyState } from '../models/PlaybackState';

export class StateManager {
  private readonly mutex = new Mutex();
  private state: PlaybackState = createEmptyState();
  private readonly storagePath: string;
  private readonly persistenceEnabled: boolean;

  constructor(storagePath: string, persistenceEnabled: boolean) {
    this.storagePath = path.resolve(storagePath);
    this.persistenceEnabled = persistenceEnabled;
  }

  async updateState(state: PlaybackState): Promise<void> {
    await this.mutex.runExclusive(async () => {
      this.state = state;
    });
  }

  async getState(): Promise<PlaybackState> {
    return this.mutex.runExclusive(async () => this.state);
  }

  async persistState(): Promise<void> {
    if (!this.persistenceEnabled) {
      return;
    }
    const snapshot = await this.getState();
    try {
      await fs.mkdir(path.dirname(this.storagePath), { recursive: true });
      await fs.writeFile(this.storagePath, JSON.stringify(snapshot, null, 2), 'utf-8');
    } catch (error) {
      console.error('Failed to persist playback state', error);
    }
  }

  async restoreState(): Promise<PlaybackState | null> {
    if (!this.persistenceEnabled) {
      return null;
    }
    try {
      const content = await fs.readFile(this.storagePath, 'utf-8');
      if (!content.trim()) {
        return null;
      }
      const parsed = JSON.parse(content) as PlaybackState;
      await this.updateState(parsed);
      return parsed;
    } catch (error) {
      return null;
    }
  }
}
