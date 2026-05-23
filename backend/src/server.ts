import { createApp } from './app';
import { pool } from '@utils/db';
import { logger } from '@utils/logger';

const PORT = parseInt(process.env.PORT ?? '3001', 10);

const app = createApp();

const server = app.listen(PORT, () => {
  logger.info(`BillGuard API listening on port ${PORT}`, {
    env: process.env.NODE_ENV,
    port: PORT,
  });
});

// ── Graceful shutdown ─────────────────────────────────────────────────────────
// Kubernetes sends SIGTERM before SIGKILL; we have ~30s to drain connections.

async function shutdown(signal: string) {
  logger.info(`Received ${signal}, shutting down gracefully`);

  server.close(async (err) => {
    if (err) {
      logger.error('Error closing HTTP server', { error: err.message });
      process.exit(1);
    }

    try {
      await pool.end();
      logger.info('Database pool closed');
      process.exit(0);
    } catch (dbErr) {
      logger.error('Error closing database pool', { error: (dbErr as Error).message });
      process.exit(1);
    }
  });

  // Force exit if drain takes too long
  setTimeout(() => {
    logger.error('Graceful shutdown timed out, forcing exit');
    process.exit(1);
  }, 25_000);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

process.on('uncaughtException', (err) => {
  logger.error('Uncaught exception', { error: err.message, stack: err.stack });
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  logger.error('Unhandled promise rejection', { reason });
  process.exit(1);
});
