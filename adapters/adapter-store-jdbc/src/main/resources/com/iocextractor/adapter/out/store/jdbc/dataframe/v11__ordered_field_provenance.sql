ALTER TABLE confirmation_receipt
ADD COLUMN payload_version INTEGER NOT NULL DEFAULT 1 CHECK (payload_version IN (1, 2));

ALTER TABLE canonical_observation_commit
ADD COLUMN updated INTEGER NOT NULL DEFAULT 0 CHECK (updated >= 0);

ALTER TABLE canonical_observation_commit
ADD COLUMN metadata_only INTEGER NOT NULL DEFAULT 0 CHECK (metadata_only >= 0);

CREATE TABLE confirmation_receipt_field_position (
    receipt_id TEXT NOT NULL,
    artifact TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    field_name TEXT NOT NULL,
    occurrence_position INTEGER NOT NULL CHECK (occurrence_position >= 0),
    PRIMARY KEY (receipt_id, artifact, ordinal, field_name),
    FOREIGN KEY (receipt_id, artifact)
        REFERENCES confirmation_receipt_artifact(receipt_id, artifact) ON DELETE CASCADE
);

CREATE TABLE canonical_lifecycle_field_origin_history (
    artifact TEXT NOT NULL,
    lifecycle_id INTEGER NOT NULL CHECK (lifecycle_id > 0),
    field_name TEXT NOT NULL,
    admission_order INTEGER NOT NULL CHECK (admission_order > 0),
    occurrence_position INTEGER NOT NULL CHECK (occurrence_position >= 0),
    occurrence_id TEXT NOT NULL,
    archived_at_ms INTEGER NOT NULL CHECK (archived_at_ms >= 0),
    PRIMARY KEY (artifact, lifecycle_id, field_name),
    FOREIGN KEY (occurrence_id) REFERENCES registered_observation(occurrence_id)
);
