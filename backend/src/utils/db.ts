import { Pool, PoolClient } from 'pg';
import { logger } from '@utils/logger';

if (!process.env.DATABASE_URL) {
  throw new Error('DATABASE_URL environment variable is required');
}

export const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 20,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 5_000,
});

pool.on('error', (err) => {
  logger.error('Unexpected database pool error', { error: err.message });
});

export async function checkDatabaseHealth(): Promise<boolean> {
  let client: PoolClient | null = null;
  try {
    client = await pool.connect();
    await client.query('SELECT 1');
    return true;
  } catch (err) {
    logger.error('Database health check failed', { error: (err as Error).message });
    return false;
  } finally {
    client?.release();
  }
}

/**
 * Run a query with automatic connection management.
 * Prefer this over pool.query for consistent error logging.
 */
export async function query<T extends object = Record<string, unknown>>(
  text: string,
  params?: unknown[]
): Promise<T[]> {
  const start = Date.now();
  try {
    const result = await pool.query<T>(text, params);
    logger.debug('Query executed', { duration: Date.now() - start, rows: result.rowCount });
    return result.rows;
  } catch (err) {
    logger.error('Query failed', { text, error: (err as Error).message });
    throw err;
  }
}

/**
 * Run multiple queries in a single transaction.
 */
export async function withTransaction<T>(
  fn: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}
