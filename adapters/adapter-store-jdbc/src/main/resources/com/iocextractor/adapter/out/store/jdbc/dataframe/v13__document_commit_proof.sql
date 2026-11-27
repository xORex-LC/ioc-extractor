ALTER TABLE canonical_observation ADD COLUMN document_plan_id TEXT
    CHECK (document_plan_id IS NULL OR length(trim(document_plan_id)) > 0);
ALTER TABLE canonical_observation ADD COLUMN document_lifecycle INTEGER
    CHECK ((document_plan_id IS NULL AND document_lifecycle IS NULL)
        OR (document_plan_id IS NOT NULL AND document_lifecycle IS NOT NULL AND document_lifecycle IN (0, 1)));

CREATE TABLE document_confirmation_receipt_pin (
    observation_id TEXT PRIMARY KEY REFERENCES registered_observation(occurrence_id) ON DELETE RESTRICT,
    receipt_id TEXT NOT NULL REFERENCES confirmation_receipt(receipt_id) ON DELETE RESTRICT
);

CREATE INDEX ix_document_confirmation_receipt_pin_receipt
ON document_confirmation_receipt_pin(receipt_id);
