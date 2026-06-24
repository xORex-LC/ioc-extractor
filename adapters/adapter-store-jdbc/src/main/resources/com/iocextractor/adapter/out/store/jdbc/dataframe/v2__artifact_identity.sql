CREATE TABLE artifact_identity (
    artifact TEXT PRIMARY KEY,
    identity_hash TEXT NOT NULL,
    epoch INTEGER NOT NULL DEFAULT 1,
    applied_at TEXT NOT NULL
);
