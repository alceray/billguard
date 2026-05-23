import { Request, Response, NextFunction } from 'express';
import { expressjwt, GetVerificationKey } from 'express-jwt';
import jwksRsa from 'jwks-rsa';
import { z } from 'zod';

const envSchema = z.object({
  AUTH0_DOMAIN: z.string().min(1),
  AUTH0_AUDIENCE: z.string().min(1),
});

const env = envSchema.parse(process.env);

/**
 * Validates Auth0 JWTs using JWKS. Attaches decoded payload to req.auth.
 */
export const validateJwt = expressjwt({
  secret: jwksRsa.expressJwtSecret({
    cache: true,
    rateLimit: true,
    jwksRequestsPerMinute: 5,
    jwksUri: `https://${env.AUTH0_DOMAIN}/.well-known/jwks.json`,
  }) as GetVerificationKey,
  audience: env.AUTH0_AUDIENCE,
  issuer: `https://${env.AUTH0_DOMAIN}/`,
  algorithms: ['RS256'],
});

export interface AuthenticatedRequest extends Request {
  auth?: {
    sub: string;
    email?: string;
    [key: string]: unknown;
  };
}

/**
 * Convenience middleware that returns 401 if auth is missing.
 * Use after validateJwt.
 */
export function requireAuth(
  req: AuthenticatedRequest,
  res: Response,
  next: NextFunction
): void {
  if (!req.auth?.sub) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }
  next();
}
