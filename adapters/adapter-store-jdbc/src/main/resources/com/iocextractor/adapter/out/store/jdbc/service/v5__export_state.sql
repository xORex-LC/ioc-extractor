CREATE TABLE export_run (
    run_id TEXT PRIMARY KEY,
    profile TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('STARTED', 'STAGED', 'AVAILABLE', 'COMPLETED', 'SKIPPED', 'FAILED')),
    slice_name TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    manifest_sha256 TEXT,
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reason TEXT,
    CHECK (status NOT IN ('STAGED', 'AVAILABLE', 'COMPLETED') OR manifest_sha256 IS NOT NULL),
    CHECK (status <> 'FAILED' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);

CREATE INDEX ix_export_run_status ON export_run(status);

CREATE UNIQUE INDEX ux_export_run_active_singleton
ON export_run ((1))
WHERE status IN ('STARTED', 'STAGED', 'AVAILABLE');

CREATE TABLE export_progress (
    profile TEXT NOT NULL,
    artifact TEXT NOT NULL,
    last_revision INTEGER NOT NULL CHECK (last_revision >= 0),
    last_sha256 TEXT NOT NULL,
    last_slice_id TEXT NOT NULL,
    plan_hash TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (profile, artifact)
);
