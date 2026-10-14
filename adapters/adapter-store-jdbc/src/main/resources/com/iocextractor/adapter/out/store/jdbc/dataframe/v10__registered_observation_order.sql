CREATE TABLE observation_order_control (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    namespace_id TEXT NOT NULL UNIQUE CHECK (length(namespace_id) = 32),
    next_order INTEGER NOT NULL CHECK (next_order > 0)
);

INSERT INTO observation_order_control(singleton_id, namespace_id, next_order)
VALUES (1, lower(hex(randomblob(16))), 1);

CREATE TABLE registered_observation (
    occurrence_id TEXT PRIMARY KEY CHECK (length(trim(occurrence_id)) > 0),
    admission_order INTEGER NOT NULL UNIQUE CHECK (admission_order > 0),
    origin_kind TEXT NOT NULL CHECK (origin_kind IN ('DOCUMENT', 'MANAGED_IMPORT', 'ONESHOT')),
    registered_at_ms INTEGER NOT NULL CHECK (registered_at_ms >= 0),
    terminal_at_ms INTEGER CHECK (terminal_at_ms >= registered_at_ms)
);

CREATE INDEX ix_registered_observation_terminal
ON registered_observation(terminal_at_ms, admission_order);

CREATE TABLE canonical_lifecycle_field_origin (
    artifact TEXT NOT NULL,
    lifecycle_id INTEGER NOT NULL CHECK (lifecycle_id > 0),
    field_name TEXT NOT NULL,
    admission_order INTEGER NOT NULL CHECK (admission_order > 0),
    occurrence_position INTEGER NOT NULL CHECK (occurrence_position >= 0),
    occurrence_id TEXT NOT NULL,
    PRIMARY KEY (artifact, lifecycle_id, field_name),
    FOREIGN KEY (occurrence_id) REFERENCES registered_observation(occurrence_id)
);

CREATE TABLE canonical_compat_field_origin (
    artifact TEXT NOT NULL,
    row_key TEXT NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK (identity_epoch > 0),
    field_name TEXT NOT NULL,
    admission_order INTEGER NOT NULL CHECK (admission_order > 0),
    occurrence_position INTEGER NOT NULL CHECK (occurrence_position >= 0),
    occurrence_id TEXT NOT NULL,
    PRIMARY KEY (artifact, row_key, identity_epoch, field_name),
    FOREIGN KEY (occurrence_id) REFERENCES registered_observation(occurrence_id)
);
