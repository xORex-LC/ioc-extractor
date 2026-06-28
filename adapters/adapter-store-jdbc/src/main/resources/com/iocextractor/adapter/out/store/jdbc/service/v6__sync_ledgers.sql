CREATE TABLE remote_fetch_ledger (
    remote_path TEXT NOT NULL,
    remote_size INTEGER NOT NULL CHECK (remote_size >= 0),
    remote_mtime TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('FETCHED', 'FAILED', 'SKIPPED')),
    local_path TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    fetched_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (remote_path, remote_size, remote_mtime),
    CHECK (status <> 'FETCHED' OR (local_path IS NOT NULL AND length(trim(local_path)) > 0 AND fetched_at IS NOT NULL))
);

CREATE INDEX ix_remote_fetch_ledger_status ON remote_fetch_ledger(status);

CREATE TABLE publish_ledger (
    slice_id TEXT NOT NULL,
    target_id TEXT NOT NULL,
    profile TEXT NOT NULL,
    slice_name TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    remote_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error TEXT,
    remote_verification TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (slice_id, target_id),
    CHECK (length(trim(slice_id)) > 0),
    CHECK (length(trim(target_id)) > 0),
    CHECK (length(trim(profile)) > 0),
    CHECK (length(trim(slice_name)) > 0),
    CHECK (manifest_sha256 GLOB '[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]'),
    CHECK (length(trim(endpoint)) > 0),
    CHECK (length(trim(remote_path)) > 0)
);

CREATE INDEX ix_publish_ledger_status ON publish_ledger(status);
CREATE INDEX ix_publish_ledger_slice ON publish_ledger(slice_id);
CREATE INDEX ix_publish_ledger_profile_status ON publish_ledger(profile, status);
