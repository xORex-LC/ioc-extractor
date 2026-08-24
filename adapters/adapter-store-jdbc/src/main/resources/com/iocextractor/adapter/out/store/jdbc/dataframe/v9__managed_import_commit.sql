CREATE TABLE import_commit (
    delivery_id TEXT PRIMARY KEY,
    sequence_no INTEGER NOT NULL UNIQUE CHECK (sequence_no > 0),
    observation_id TEXT NOT NULL UNIQUE,
    source_id TEXT NOT NULL,
    snapshot_sha256 TEXT NOT NULL,
    snapshot_size INTEGER NOT NULL CHECK (snapshot_size >= 0),
    contract_id TEXT NOT NULL,
    contract_version INTEGER NOT NULL CHECK (contract_version > 0),
    contract_fingerprint TEXT NOT NULL,
    stage_sha256 TEXT NOT NULL,
    outcome TEXT NOT NULL,
    effective_as_of_ms INTEGER NOT NULL,
    accepted_rows INTEGER NOT NULL CHECK (accepted_rows >= 0),
    rejected_rows INTEGER NOT NULL CHECK (rejected_rows >= 0),
    public_mutations INTEGER NOT NULL CHECK (public_mutations >= 0),
    committed_at_ms INTEGER NOT NULL,
    purge_after_ms INTEGER NOT NULL CHECK (purge_after_ms >= committed_at_ms)
);

CREATE TABLE import_commit_artifact (
    delivery_id TEXT NOT NULL,
    artifact TEXT NOT NULL,
    public_mutation INTEGER NOT NULL CHECK (public_mutation IN (0, 1)),
    deadline_changed INTEGER NOT NULL CHECK (deadline_changed IN (0, 1)),
    projection_generation INTEGER,
    PRIMARY KEY (delivery_id, artifact),
    FOREIGN KEY (delivery_id) REFERENCES import_commit(delivery_id) ON DELETE CASCADE,
    CHECK ((public_mutation = 1) = (projection_generation IS NOT NULL)),
    CHECK (projection_generation IS NULL OR projection_generation > 0)
);

CREATE TABLE import_row_rejection (
    delivery_id TEXT NOT NULL,
    rejection_ordinal INTEGER NOT NULL CHECK (rejection_ordinal > 0),
    source_row_number INTEGER NOT NULL CHECK (source_row_number > 0),
    artifact TEXT,
    diagnostic_code TEXT NOT NULL,
    PRIMARY KEY (delivery_id, rejection_ordinal),
    FOREIGN KEY (delivery_id) REFERENCES import_commit(delivery_id) ON DELETE CASCADE
);

CREATE TABLE import_slot_resolution (
    delivery_id TEXT NOT NULL,
    resolution_ordinal INTEGER NOT NULL CHECK (resolution_ordinal > 0),
    source_row_number INTEGER NOT NULL CHECK (source_row_number > 0),
    artifact TEXT NOT NULL,
    lifecycle_id INTEGER NOT NULL CHECK (lifecycle_id > 0),
    requested_slot INTEGER NOT NULL CHECK (requested_slot > 0),
    assigned_slot INTEGER NOT NULL CHECK (assigned_slot > 0),
    outcome TEXT NOT NULL CHECK (outcome IN (
        'EXACT', 'OCCUPIED_FALLBACK', 'SURVIVOR_MATCH', 'SURVIVOR_MISMATCH_PRESERVED')),
    PRIMARY KEY (delivery_id, resolution_ordinal),
    UNIQUE (delivery_id, artifact, lifecycle_id),
    FOREIGN KEY (delivery_id) REFERENCES import_commit(delivery_id) ON DELETE CASCADE
);

CREATE INDEX ix_import_commit_purge
ON import_commit(purge_after_ms, sequence_no);

CREATE INDEX ix_import_rejection_delivery
ON import_row_rejection(delivery_id, source_row_number);

CREATE INDEX ix_import_slot_resolution_delivery
ON import_slot_resolution(delivery_id, source_row_number);
