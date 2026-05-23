# ── Stage 1: deps ─────────────────────────────────────────────────────────────
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --only=production && cp -r node_modules /tmp/prod_modules
RUN npm ci

# ── Stage 2: build ────────────────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
FROM node:20-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -g 1001 -S billguard && adduser -u 1001 -S billguard -G billguard
USER billguard

COPY --from=build --chown=billguard:billguard /app/dist ./dist
COPY --from=deps --chown=billguard:billguard /tmp/prod_modules ./node_modules
COPY --chown=billguard:billguard migrations ./migrations

EXPOSE 3001

# Graceful shutdown needs the real Node process, not a shell wrapper
CMD ["node", "dist/server.js"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:3001/health || exit 1
