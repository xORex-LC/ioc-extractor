ALTER TABLE ingestion_ledger RENAME TO ingestion_ledger_content_keyed;

DROP INDEX IF EXISTS ix_ingestion_ledger_status;

CREATE TABLE ingestion_ledger (
    observation_id TEXT PRIMARY KEY,
    source_key TEXT NOT NULL,
    status TEXT NOT NULL,
    original_path TEXT NOT NULL,
    processing_path TEXT NOT NULL,
    archived_path TEXT,
    detected_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reason TEXT
);

INSERT INTO ingestion_ledger (
    observation_id, source_key, status, original_path, processing_path,
    archived_path, detected_at, updated_at, reason)
SELECT 'legacy:' || source_key, source_key, status, original_path, processing_path,
       archived_path, detected_at, updated_at, reason
FROM ingestion_ledger_content_keyed;

DROP TABLE ingestion_ledger_content_keyed;

CREATE INDEX ix_ingestion_ledger_status
ON ingestion_ledger(status, detected_at, observation_id);

CREATE INDEX ix_ingestion_ledger_source
ON ingestion_ledger(source_key, detected_at, observation_id);
