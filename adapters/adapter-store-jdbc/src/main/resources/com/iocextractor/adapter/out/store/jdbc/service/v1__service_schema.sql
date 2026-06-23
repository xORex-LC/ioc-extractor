CREATE TABLE ingestion_ledger (
    source_key TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    original_path TEXT NOT NULL,
    processing_path TEXT NOT NULL,
    archived_path TEXT,
    detected_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reason TEXT
);

CREATE INDEX ix_ingestion_ledger_status ON ingestion_ledger(status);

CREATE TABLE ingestion_partition (
    source_key TEXT NOT NULL REFERENCES ingestion_ledger(source_key) ON DELETE CASCADE,
    partition_path TEXT NOT NULL,
    PRIMARY KEY (source_key, partition_path)
);

CREATE TABLE legacy_imports (
    name TEXT PRIMARY KEY,
    source_path TEXT NOT NULL,
    checksum TEXT NOT NULL,
    status TEXT NOT NULL,
    completed_at TEXT
);
