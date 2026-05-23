import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import { validateJwt } from '@middleware/auth';
import { errorHandler } from '@middleware/errorHandler';
import { authRouter } from '@modules/auth/auth.routes';
import { logger } from '@utils/logger';

export function createApp() {
  const app = express();

  // ── Security headers ──────────────────────────
  app.use(helmet());

  // ── CORS ──────────────────────────────────────
  app.use(
    cors({
      origin: process.env.FRONTEND_URL ?? 'http://localhost:5173',
      credentials: true,
    })
  );

  // ── Body parsing ──────────────────────────────
  app.use(express.json({ limit: '1mb' }));

  // ── Rate limiting ─────────────────────────────
  app.use(
    rateLimit({
      windowMs: 15 * 60 * 1000, // 15 minutes
      max: 200,
      standardHeaders: true,
      legacyHeaders: false,
    })
  );

  // ── Health & readiness (no auth — used by K8s probes) ──
  app.get('/health', (_req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
  });

  app.get('/ready', async (_req, res) => {
    const { checkDatabaseHealth } = await import('@utils/db');
    const dbHealthy = await checkDatabaseHealth();
    if (!dbHealthy) {
      res.status(503).json({ status: 'unavailable', reason: 'database' });
      return;
    }
    res.json({ status: 'ready' });
  });

  // ── JWT validation for all /api routes ────────
  app.use('/api', validateJwt);

  // ── Routers ───────────────────────────────────
  app.use('/api/auth', authRouter);

  // Phase 2: plaid routes
  // app.use('/api/plaid', plaidRouter);

  // Phase 3: subscriptions + ai routes
  // app.use('/api/subscriptions', subscriptionsRouter);

  // ── 404 ───────────────────────────────────────
  app.use((_req, res) => {
    res.status(404).json({ error: 'Not found' });
  });

  // ── Global error handler ─────────────────────
  app.use(errorHandler);

  logger.info('Express app initialised');
  return app;
}
