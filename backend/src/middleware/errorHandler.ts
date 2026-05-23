import { Request, Response, NextFunction } from 'express';
import { ZodError } from 'zod';
import { logger } from '@utils/logger';

export interface AppError extends Error {
  statusCode?: number;
  code?: string;
}

export function errorHandler(
  err: AppError,
  req: Request,
  res: Response,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _next: NextFunction
): void {
  // Zod validation errors → 400
  if (err instanceof ZodError) {
    res.status(400).json({
      error: 'Validation error',
      details: err.flatten().fieldErrors,
    });
    return;
  }

  // Auth0 JWT errors → 401
  if (err.name === 'UnauthorizedError') {
    res.status(401).json({ error: 'Invalid or missing token' });
    return;
  }

  const statusCode = err.statusCode ?? 500;
  const message = statusCode < 500 ? err.message : 'Internal server error';

  if (statusCode >= 500) {
    logger.error('Unhandled error', {
      error: err.message,
      stack: err.stack,
      path: req.path,
      method: req.method,
    });
  }

  res.status(statusCode).json({ error: message, code: err.code });
}

/** Throw this to return a specific HTTP status with a message. */
export class HttpError extends Error {
  constructor(
    public statusCode: number,
    message: string,
    public code?: string
  ) {
    super(message);
    this.name = 'HttpError';
  }
}
