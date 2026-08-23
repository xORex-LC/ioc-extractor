CREATE TABLE canonical_lifecycle_control (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    state TEXT NOT NULL CHECK (state IN ('DISABLED_COMPATIBLE', 'ACTIVATING', 'ACTIVE')),
    policy_fingerprint TEXT,
    activated_at_ms INTEGER,
    safe_time_high_water_ms INTEGER,
    clamp_started_at_ms INTEGER,
    CHECK (
        (state = 'DISABLED_COMPATIBLE'
            AND policy_fingerprint IS NULL
            AND activated_at_ms IS NULL)
        OR (state = 'ACTIVATING'
            AND policy_fingerprint IS NOT NULL
            AND activated_at_ms IS NULL)
        OR (state = 'ACTIVE'
            AND policy_fingerprint IS NOT NULL
            AND activated_at_ms IS NOT NULL)
    )
);

INSERT INTO canonical_lifecycle_control(singleton_id, state)
VALUES (1, 'DISABLED_COMPATIBLE');

CREATE TABLE lifecycle_activation_progress (
    artifact TEXT PRIMARY KEY,
    after_row_id INTEGER,
    expired_count INTEGER NOT NULL DEFAULT 0 CHECK (expired_count >= 0),
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE lifecycle_id_allocator (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    next_value INTEGER NOT NULL CHECK (next_value > 0),
    updated_at_ms INTEGER NOT NULL
);

INSERT INTO lifecycle_id_allocator(singleton_id, next_value, updated_at_ms)
VALUES (1, 1, 0);

CREATE TABLE artifact_id_allocator (
    artifact TEXT PRIMARY KEY,
    strategy TEXT NOT NULL CHECK (strategy IN ('ASCENDING', 'DESCENDING')),
    next_value INTEGER NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK (identity_epoch > 0),
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE artifact_projection_state (
    artifact TEXT PRIMARY KEY,
    required_generation INTEGER NOT NULL DEFAULT 0 CHECK (required_generation >= 0),
    projected_generation INTEGER NOT NULL DEFAULT 0 CHECK (projected_generation >= 0),
    requested_at_ms INTEGER,
    projected_at_ms INTEGER,
    last_error_code TEXT,
    CHECK (projected_generation <= required_generation)
);

CREATE TABLE canonical_observation (
    observation_id TEXT PRIMARY KEY,
    source_key TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('OPEN', 'TERMINAL')),
    started_at_ms INTEGER NOT NULL,
    terminal_at_ms INTEGER,
    purge_after_ms INTEGER,
    CHECK (
        (state = 'OPEN' AND terminal_at_ms IS NULL AND purge_after_ms IS NULL)
        OR (state = 'TERMINAL'
            AND terminal_at_ms IS NOT NULL
            AND purge_after_ms IS NOT NULL
            AND purge_after_ms > terminal_at_ms)
    )
);

CREATE TABLE canonical_observation_commit (
    observation_id TEXT NOT NULL
        REFERENCES canonical_observation(observation_id) ON DELETE CASCADE,
    artifact TEXT NOT NULL,
    committed_at_ms INTEGER NOT NULL,
    effective_as_of_ms INTEGER NOT NULL,
    inserted INTEGER NOT NULL CHECK (inserted >= 0),
    renewed INTEGER NOT NULL CHECK (renewed >= 0),
    restarted INTEGER NOT NULL CHECK (restarted >= 0),
    artifact_revision INTEGER NOT NULL CHECK (artifact_revision >= 0),
    projection_generation INTEGER NOT NULL CHECK (projection_generation >= 0),
    PRIMARY KEY (observation_id, artifact)
);

CREATE INDEX ix_canonical_observation_state
ON canonical_observation(state, started_at_ms, observation_id);

CREATE INDEX ix_canonical_observation_retention
ON canonical_observation(state, purge_after_ms, observation_id);

CREATE TABLE lifecycle_reconcile_cycle (
    cycle_id INTEGER PRIMARY KEY AUTOINCREMENT,
    cycle_as_of_ms INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('STARTED', 'COMPLETED', 'FAILED')),
    started_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER,
    expired_count INTEGER NOT NULL DEFAULT 0 CHECK (expired_count >= 0),
    affected_artifact_count INTEGER NOT NULL DEFAULT 0 CHECK (affected_artifact_count >= 0),
    failure_code TEXT,
    CHECK (
        (state = 'STARTED' AND completed_at_ms IS NULL AND failure_code IS NULL)
        OR (state = 'COMPLETED' AND completed_at_ms IS NOT NULL AND failure_code IS NULL)
        OR (state = 'FAILED' AND completed_at_ms IS NOT NULL AND failure_code IS NOT NULL)
    )
);

CREATE INDEX ix_lifecycle_reconcile_cycle_state
ON lifecycle_reconcile_cycle(state, started_at_ms, cycle_id);

CREATE TABLE confirmation_receipt (
    receipt_id TEXT PRIMARY KEY,
    source_key TEXT NOT NULL,
    processing_policy_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('STAGING', 'COMPLETE')),
    expected_artifacts INTEGER NOT NULL CHECK (expected_artifacts >= 0),
    row_count INTEGER NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    completed_at_ms INTEGER,
    purge_after_ms INTEGER,
    CHECK (
        (state = 'STAGING' AND completed_at_ms IS NULL AND purge_after_ms IS NULL)
        OR (state = 'COMPLETE'
            AND completed_at_ms IS NOT NULL
            AND purge_after_ms IS NOT NULL
            AND purge_after_ms > completed_at_ms)
    )
);

CREATE TABLE confirmation_receipt_artifact (
    receipt_id TEXT NOT NULL
        REFERENCES confirmation_receipt(receipt_id) ON DELETE CASCADE,
    artifact TEXT NOT NULL,
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    staged_at_ms INTEGER NOT NULL,
    PRIMARY KEY (receipt_id, artifact)
);

CREATE INDEX ix_confirmation_receipt_lookup
ON confirmation_receipt(source_key, processing_policy_fingerprint, state, purge_after_ms);

CREATE INDEX ix_confirmation_receipt_retention
ON confirmation_receipt(purge_after_ms, receipt_id)
WHERE state = 'COMPLETE';
