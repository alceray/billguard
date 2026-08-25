-- Phase 3: recurring-charge detection and Plaid personal finance categories.

ALTER TABLE transactions
  ADD COLUMN pfc_primary TEXT,
  ADD COLUMN pfc_detailed TEXT,
  ADD COLUMN transaction_datetime TIMESTAMPTZ;

CREATE INDEX idx_transactions_pfc_detailed ON transactions (pfc_detailed);

ALTER TABLE subscriptions RENAME TO recurring_charges;
ALTER INDEX idx_subscriptions_user_id RENAME TO idx_recurring_charges_user_id;
ALTER INDEX idx_subscriptions_status RENAME TO idx_recurring_charges_status;

ALTER TABLE recurring_charges
  ADD COLUMN merchant_key TEXT,
  ADD COLUMN detection_state TEXT NOT NULL DEFAULT 'suspected',
  ADD COLUMN category TEXT NOT NULL DEFAULT 'other',
  ADD COLUMN category_source TEXT NOT NULL DEFAULT 'model',
  ADD COLUMN previous_amount NUMERIC(12, 2),
  ADD COLUMN amount_changed_at TIMESTAMPTZ,
  ADD COLUMN is_variable_amount BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN source_transaction_id UUID REFERENCES transactions (id) ON DELETE SET NULL,
  ADD COLUMN classification_prompt_version TEXT,
  ADD COLUMN classification_reasoning TEXT,
  ADD COLUMN feedback_type TEXT,
  ADD CONSTRAINT chk_recurring_charge_feedback_type
    CHECK (feedback_type IS NULL OR feedback_type IN ('confirmation', 'false_positive', 'false_negative'));

UPDATE recurring_charges
   SET merchant_key = trim(BOTH '-' FROM regexp_replace(lower(merchant_name), '[^a-z0-9]+', '-', 'g'));

ALTER TABLE recurring_charges
  ALTER COLUMN merchant_key SET NOT NULL,
  ALTER COLUMN confidence_score SET DEFAULT 0.50,
  ADD CONSTRAINT chk_recurring_charge_detection_state
    CHECK (detection_state IN ('confirmed', 'provisional', 'suspected', 'user_confirmed')),
  ADD CONSTRAINT chk_recurring_charge_category
    CHECK (category IN (
      'streaming', 'ai', 'software', 'gaming', 'news_and_media', 'fitness_and_health',
      'insurance', 'storage_and_hosting', 'telecom', 'utilities', 'food_and_delivery',
      'shopping_membership', 'education', 'finance', 'housing', 'loan', 'other'
    )),
  ADD CONSTRAINT chk_recurring_charge_category_source
    CHECK (category_source IN ('rule', 'model', 'user')),
  ADD CONSTRAINT chk_recurring_charge_billing_cycle
    CHECK (billing_cycle IS NULL OR billing_cycle IN (
      'weekly', 'biweekly', 'semi_monthly', 'monthly', 'quarterly', 'annual'
    )),
  ADD CONSTRAINT chk_recurring_charge_confidence
    CHECK (confidence_score IS NULL OR confidence_score BETWEEN 0 AND 1),
  ADD CONSTRAINT uq_recurring_charges_user_merchant UNIQUE (user_id, merchant_key);

CREATE INDEX idx_recurring_charges_detection_state
  ON recurring_charges (user_id, detection_state);
CREATE INDEX idx_recurring_charges_category
  ON recurring_charges (user_id, category);
