ALTER TABLE document_admission ADD COLUMN execution_attempts INTEGER NOT NULL DEFAULT 0 CHECK (execution_attempts >= 0);
ALTER TABLE document_admission ADD COLUMN retry_after_ms INTEGER NOT NULL DEFAULT 0;
ALTER TABLE document_admission ADD COLUMN execution_failure TEXT;
CREATE INDEX ix_document_execution_order
ON document_admission(admission_order, created_at_ms, occurrence_id)
WHERE phase <> 'TERMINAL' OR registration_finalized = 0;
