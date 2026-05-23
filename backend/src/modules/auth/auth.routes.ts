import { Router, Response } from 'express';
import { z } from 'zod';
import { AuthenticatedRequest, requireAuth } from '@middleware/auth';
import { query, withTransaction } from '@utils/db';
import { HttpError } from '@middleware/errorHandler';
import { logger } from '@utils/logger';

export const authRouter = Router();

const upsertUserSchema = z.object({
  email: z.string().email(),
  name: z.string().min(1).optional(),
});

/**
 * POST /auth/me
 * Called by the frontend on first login to upsert the user record.
 * Auth0 is the source of truth for identity; we store a local copy for FK references.
 */
authRouter.post(
  '/me',
  requireAuth,
  async (req: AuthenticatedRequest, res: Response) => {
    const auth0Sub = req.auth!.sub;
    const body = upsertUserSchema.parse(req.body);

    const result = await withTransaction(async (client) => {
      return client.query<{ id: string; email: string; name: string | null; created_at: Date }>(
        `INSERT INTO users (auth0_sub, email, name)
         VALUES ($1, $2, $3)
         ON CONFLICT (auth0_sub) DO UPDATE
           SET email = EXCLUDED.email,
               name  = COALESCE(EXCLUDED.name, users.name),
               updated_at = NOW()
         RETURNING id, email, name, created_at`,
        [auth0Sub, body.email, body.name ?? null]
      );
    });

    const user = result.rows[0];
    if (!user) throw new HttpError(500, 'Failed to upsert user');

    logger.info('User upserted', { userId: user.id });
    res.json(user);
  }
);

/**
 * GET /auth/me
 * Returns the current user's profile.
 */
authRouter.get(
  '/me',
  requireAuth,
  async (req: AuthenticatedRequest, res: Response) => {
    const auth0Sub = req.auth!.sub;

    const rows = await query<{ id: string; email: string; name: string | null; created_at: Date }>(
      'SELECT id, email, name, created_at FROM users WHERE auth0_sub = $1',
      [auth0Sub]
    );

    if (!rows[0]) throw new HttpError(404, 'User not found');
    res.json(rows[0]);
  }
);