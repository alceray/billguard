-- confidence_score is deterministic recurrence evidence; model_confidence is the
-- classifier's certainty that the merchant represents a recurring charge. V1
-- described confidence_score as a classifier output, which has never been true.
-- The comments below correct that.

ALTER TABLE recurring_charges
  ADD COLUMN model_confidence NUMERIC(3, 2),
  ADD CONSTRAINT chk_recurring_charge_model_confidence
    CHECK (model_confidence IS NULL OR model_confidence BETWEEN 0 AND 1);

COMMENT ON COLUMN recurring_charges.confidence_score IS
  'Deterministic recurrence evidence, 0.00-1.00. Not a model output.';
COMMENT ON COLUMN recurring_charges.model_confidence IS
  'Classifier certainty that the merchant represents a recurring charge, 0.00-1.00. NULL when no model ran.';
