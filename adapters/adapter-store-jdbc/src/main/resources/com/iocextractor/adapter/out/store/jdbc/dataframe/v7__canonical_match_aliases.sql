ALTER TABLE artifact_identity ADD COLUMN record_definition_id TEXT;
ALTER TABLE artifact_identity ADD COLUMN record_definition_fingerprint TEXT;

CREATE TABLE canonical_match_definition (
    artifact TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    definition_fingerprint TEXT NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK (identity_epoch > 0),
    activated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (artifact, definition_id)
);

CREATE TABLE canonical_match_alias (
    artifact TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    key_canonical TEXT NOT NULL,
    lifecycle_id INTEGER NOT NULL CHECK (lifecycle_id > 0),
    canonical_row_id INTEGER NOT NULL CHECK (canonical_row_id > 0),
    PRIMARY KEY (artifact, definition_id, key_hash, key_canonical, lifecycle_id),
    FOREIGN KEY (artifact, definition_id)
        REFERENCES canonical_match_definition(artifact, definition_id)
);

CREATE INDEX ix_canonical_match_alias_lookup
ON canonical_match_alias(artifact, definition_id, key_hash, key_canonical);

CREATE INDEX ix_canonical_match_alias_lifecycle
ON canonical_match_alias(artifact, lifecycle_id);
