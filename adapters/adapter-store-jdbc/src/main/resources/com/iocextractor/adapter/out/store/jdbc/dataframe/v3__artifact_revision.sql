CREATE TABLE artifact_revision (
    artifact TEXT PRIMARY KEY,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    changed_at TEXT
);
