ALTER TABLE remote_fetch_ledger RENAME TO remote_fetch_ledger_legacy;

CREATE TABLE remote_fetch_ledger (
    source_id TEXT NOT NULL CHECK (length(trim(source_id)) > 0),
    remote_path TEXT NOT NULL,
    remote_size INTEGER NOT NULL CHECK (remote_size >= 0),
    remote_mtime TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('FETCHED', 'FAILED', 'SKIPPED')),
    local_path TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    fetched_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (source_id, remote_path, remote_size, remote_mtime),
    CHECK (status <> 'FETCHED' OR (local_path IS NOT NULL AND length(trim(local_path)) > 0 AND fetched_at IS NOT NULL))
);

CREATE TABLE remote_fetch_legacy_replay (
    source_id TEXT NOT NULL CHECK (length(trim(source_id)) > 0),
    remote_path TEXT NOT NULL,
    remote_size INTEGER NOT NULL,
    remote_mtime TEXT NOT NULL,
    approved_at TEXT NOT NULL CHECK (length(trim(approved_at)) > 0),
    PRIMARY KEY (source_id, remote_path, remote_size, remote_mtime),
    FOREIGN KEY (remote_path, remote_size, remote_mtime)
        REFERENCES remote_fetch_ledger_legacy (remote_path, remote_size, remote_mtime)
);
