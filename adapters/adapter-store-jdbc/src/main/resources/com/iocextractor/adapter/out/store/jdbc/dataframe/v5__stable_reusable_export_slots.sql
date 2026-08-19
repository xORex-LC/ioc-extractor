CREATE TABLE export_slot_assignment (
    profile TEXT NOT NULL CHECK (length(trim(profile)) > 0),
    artifact TEXT NOT NULL CHECK (length(trim(artifact)) > 0),
    lifecycle_id INTEGER NOT NULL CHECK (lifecycle_id > 0),
    slot INTEGER NOT NULL CHECK (slot > 0),
    assigned_at_ms INTEGER NOT NULL CHECK (assigned_at_ms >= 0),
    PRIMARY KEY (profile, artifact, lifecycle_id),
    UNIQUE (profile, artifact, slot)
);

CREATE TABLE export_slot_free (
    profile TEXT NOT NULL CHECK (length(trim(profile)) > 0),
    artifact TEXT NOT NULL CHECK (length(trim(artifact)) > 0),
    slot INTEGER NOT NULL CHECK (slot > 0),
    released_at_ms INTEGER NOT NULL CHECK (released_at_ms >= 0),
    PRIMARY KEY (profile, artifact, slot)
);

CREATE TABLE export_slot_state (
    profile TEXT NOT NULL CHECK (length(trim(profile)) > 0),
    artifact TEXT NOT NULL CHECK (length(trim(artifact)) > 0),
    policy_version TEXT NOT NULL CHECK (length(trim(policy_version)) > 0),
    next_slot INTEGER NOT NULL CHECK (next_slot > 0),
    source_generation INTEGER NOT NULL CHECK (source_generation >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= 0),
    PRIMARY KEY (profile, artifact)
);

CREATE INDEX ix_export_slot_assignment_slot
ON export_slot_assignment(profile, artifact, slot, lifecycle_id);
