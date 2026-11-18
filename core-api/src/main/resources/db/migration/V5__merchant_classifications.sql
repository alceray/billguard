CREATE TABLE merchant_classifications (
  merchant_key       TEXT NOT NULL,
  classifier_version TEXT NOT NULL,
  fingerprint        TEXT NOT NULL,
  display_name       TEXT NOT NULL,
  category           TEXT NOT NULL,
  confidence         NUMERIC(3, 2) NOT NULL,
  recurring_signal   BOOLEAN NOT NULL,
  reasoning          TEXT,
  model              TEXT NOT NULL,
  prompt_version     TEXT NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (merchant_key, classifier_version),
  CONSTRAINT chk_merchant_classification_confidence CHECK (confidence BETWEEN 0 AND 1),
  CONSTRAINT chk_merchant_classification_category CHECK (category IN (
    'streaming', 'ai', 'software', 'gaming', 'news_and_media', 'fitness_and_health',
    'insurance', 'storage_and_hosting', 'telecom', 'utilities', 'food_and_delivery',
    'shopping_membership', 'education', 'finance', 'housing', 'loan', 'other'
  ))
);

COMMENT ON COLUMN merchant_classifications.classifier_version IS
  'Opaque cache identity from the AI service: prompt text, contract shape and model. The key.';
COMMENT ON COLUMN merchant_classifications.model IS
  'Diagnostic only. Never part of the lookup; classifier_version already covers it.';
