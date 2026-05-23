-- BillGuard Phase 1 Schema
-- Run with: psql $DATABASE_URL -f migrations/001_initial.sql

BEGIN;

-- ──────────────────────────────────────────────
-- Users
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  auth0_sub   TEXT NOT NULL UNIQUE,         -- Auth0 subject identifier
  email       TEXT NOT NULL,
  name        TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_auth0_sub ON users (auth0_sub);

-- ──────────────────────────────────────────────
-- Bank accounts (populated in Phase 2 via Plaid)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS accounts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  plaid_account_id TEXT NOT NULL,
  plaid_item_id   TEXT NOT NULL,
  name            TEXT NOT NULL,
  official_name   TEXT,
  type            TEXT NOT NULL,            -- depository, credit, etc.
  subtype         TEXT,                     -- checking, savings, etc.
  mask            TEXT,                     -- last 4 digits
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, plaid_account_id)
);

CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts (user_id);

-- ──────────────────────────────────────────────
-- Transactions (populated in Phase 2 via Plaid)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transactions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id          UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  plaid_transaction_id TEXT NOT NULL UNIQUE,   -- idempotency key from Plaid
  amount              NUMERIC(12, 2) NOT NULL,
  currency            TEXT NOT NULL DEFAULT 'USD',
  merchant_name       TEXT,
  name                TEXT NOT NULL,
  date                DATE NOT NULL,
  pending             BOOLEAN NOT NULL DEFAULT FALSE,
  category            TEXT[],                   -- Plaid category hierarchy
  raw_payload         JSONB,                    -- full Plaid tx object for debugging
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transactions_account_id ON transactions (account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions (date DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_merchant ON transactions (merchant_name);

-- ──────────────────────────────────────────────
-- Subscriptions (detected in Phase 3 via AI)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS subscriptions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  merchant_name       TEXT NOT NULL,
  display_name        TEXT NOT NULL,            -- human-readable, may differ from merchant
  amount              NUMERIC(12, 2) NOT NULL,
  currency            TEXT NOT NULL DEFAULT 'USD',
  billing_cycle       TEXT,                     -- monthly, annual, weekly
  next_charge_date    DATE,
  confidence_score    NUMERIC(3, 2),            -- 0.00–1.00 from AI classifier
  status              TEXT NOT NULL DEFAULT 'active',  -- active, cancelled, unknown
  cancelation_method  TEXT,                     -- email, link, phone
  cancelation_url     TEXT,
  user_override       BOOLEAN,                  -- NULL = AI decision, TRUE/FALSE = user override
  first_seen_at       DATE,
  last_seen_at        DATE,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_id ON subscriptions (user_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions (status);

-- ──────────────────────────────────────────────
-- Plaid items (one per linked bank, Phase 2)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS plaid_items (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  plaid_item_id       TEXT NOT NULL UNIQUE,
  access_token_enc    TEXT NOT NULL,            -- AES-256 encrypted; never stored in plaintext
  institution_id      TEXT,
  institution_name    TEXT,
  cursor              TEXT,                     -- Plaid sync cursor for incremental updates
  last_synced_at      TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_plaid_items_user_id ON plaid_items (user_id);

-- ──────────────────────────────────────────────
-- Webhook events log (idempotency, Phase 2)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS webhook_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id        TEXT NOT NULL UNIQUE,         -- Plaid's idempotency key
  webhook_type    TEXT NOT NULL,
  webhook_code    TEXT NOT NULL,
  item_id         TEXT,
  payload         JSONB NOT NULL,
  processed       BOOLEAN NOT NULL DEFAULT FALSE,
  processed_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_webhook_events_processed ON webhook_events (processed, created_at);

COMMIT;
