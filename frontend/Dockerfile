# ── Stage 1: deps ─────────────────────────────────────────────────────────────
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
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

RUN addgroup -g 1001 -S billguard && adduser -u 1001 -S billguard -G billguard
USER billguard

COPY --from=build --chown=billguard:billguard /app/build ./build
COPY --from=build --chown=billguard:billguard /app/node_modules ./node_modules
COPY --from=build --chown=billguard:billguard /app/package.json ./package.json

EXPOSE 3000

CMD ["node", "build"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:3000 || exit 1