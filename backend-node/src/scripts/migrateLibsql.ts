import fs from 'node:fs/promises';
import path from 'node:path';

import { createClient } from '@libsql/client';
import dotenv from 'dotenv';

import { logger } from '../utils/logger';

dotenv.config();

async function run(): Promise<void> {
  const url = process.env.ROOMS_LIBSQL_URL ?? process.env.TURSO_DATABASE_URL;
  const authToken = process.env.ROOMS_LIBSQL_AUTH_TOKEN ?? process.env.TURSO_AUTH_TOKEN;

  if (!url) {
    throw new Error('ROOMS_LIBSQL_URL (or TURSO_DATABASE_URL) is required to run migrations');
  }

  const client = createClient({ url, authToken });
  await client.execute('PRAGMA foreign_keys = ON');

  const sqlDir = path.resolve(process.cwd(), 'sql');
  const files = await fs
    .readdir(sqlDir)
    .then((entries) => entries.filter((file) => file.endsWith('.sql')).sort())
    .catch((error) => {
      throw new Error(`Unable to read SQL directory at ${sqlDir}: ${error instanceof Error ? error.message : String(error)}`);
    });

  if (!files.length) {
    logger.info('No SQL migrations found');
    return;
  }

  for (const file of files) {
    const filePath = path.join(sqlDir, file);
    const contents = await fs.readFile(filePath, 'utf-8');
    const statements = contents
      .split(/;\s*(?:\n|$)/)
      .map((statement) => statement.trim())
      .filter(Boolean);

    logger.info({ file }, 'Applying migration');
    for (const statement of statements) {
      await client.execute(statement);
    }
  }

  logger.info('Migrations applied successfully');
}

run().catch((error) => {
  logger.error({ error }, 'Failed to apply migrations');
  process.exit(1);
});
