-- Phase 2: reliable Plaid ingestion and a transactional Postgres job queue.

ALTER TABLE transactions
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN pending_transaction_id TEXT,
  ADD COLUMN merchant_key TEXT;

CREATE INDEX idx_transactions_merchant_key ON transactions (merchant_key);
CREATE INDEX idx_transactions_user_feed ON transactions (date DESC, id DESC);

ALTER TABLE plaid_items
  ADD COLUMN status TEXT NOT NULL DEFAULT 'healthy',
  ADD COLUMN last_error TEXT,
  ADD CONSTRAINT chk_plaid_items_status
    CHECK (status IN ('healthy', 'item_login_required', 'error'));

ALTER TABLE accounts
  ADD CONSTRAINT fk_accounts_plaid_item
    FOREIGN KEY (plaid_item_id)
    REFERENCES plaid_items (plaid_item_id)
    ON DELETE CASCADE;

CREATE TABLE job_queue (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  type        TEXT NOT NULL,
  dedupe_key  TEXT NOT NULL,
  payload     JSONB NOT NULL DEFAULT '{}',
  status      TEXT NOT NULL DEFAULT 'pending',
  attempts    INT NOT NULL DEFAULT 0,
  run_after   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_error  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_job_queue_status CHECK (status IN ('pending', 'running', 'done', 'dead')),
  CONSTRAINT chk_job_queue_attempts CHECK (attempts >= 0)
);

-- One active job per target collapses webhook bursts while allowing future re-enqueue.
CREATE UNIQUE INDEX uq_job_queue_inflight ON job_queue (type, dedupe_key)
  WHERE status IN ('pending', 'running');

CREATE INDEX idx_job_queue_claimable ON job_queue (status, run_after)
  WHERE status = 'pending';

CREATE INDEX idx_job_queue_stale_running ON job_queue (updated_at)
  WHERE status = 'running';
