CREATE TABLE import_delivery (
    sequence_no INTEGER PRIMARY KEY AUTOINCREMENT,
    delivery_id TEXT NOT NULL UNIQUE CHECK (length(trim(delivery_id)) > 0),
    source_id TEXT NOT NULL CHECK (length(trim(source_id)) > 0),
    candidate_token TEXT NOT NULL CHECK (length(trim(candidate_token)) > 0),
    replay_of TEXT,
    state TEXT NOT NULL CHECK (state IN (
        'DETECTED', 'CLAIMING', 'CLAIMED', 'SNAPSHOT_PINNED',
        'CONTRACT_PINNED', 'STAGING', 'STAGED', 'PROMOTING',
        'CANONICAL_COMMITTED', 'FINALIZING', 'TERMINAL')),
    terminal_outcome TEXT CHECK (terminal_outcome IN (
        'SUCCEEDED', 'COMPLETED_WITH_ERRORS', 'REJECTED')),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    snapshot_locator TEXT,
    snapshot_sha256 TEXT,
    snapshot_size INTEGER CHECK (snapshot_size >= 0),
    stage_locator TEXT,
    stage_sha256 TEXT,
    stage_source_rows INTEGER CHECK (stage_source_rows >= 0),
    stage_accepted_rows INTEGER CHECK (stage_accepted_rows >= 0),
    stage_rejected_rows INTEGER CHECK (stage_rejected_rows >= 0),
    contract_id TEXT,
    contract_version INTEGER CHECK (contract_version > 0),
    contract_fingerprint TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at_ms INTEGER,
    last_error_code TEXT,
    public_mutations INTEGER CHECK (public_mutations >= 0),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= created_at_ms),
    terminal_at_ms INTEGER,
    purge_after_ms INTEGER,
    CHECK ((state = 'TERMINAL') = (terminal_outcome IS NOT NULL)),
    CHECK ((snapshot_locator IS NULL AND snapshot_sha256 IS NULL AND snapshot_size IS NULL)
        OR (snapshot_locator IS NOT NULL AND snapshot_sha256 IS NOT NULL AND snapshot_size IS NOT NULL)),
    CHECK ((contract_id IS NULL AND contract_version IS NULL AND contract_fingerprint IS NULL)
        OR (contract_id IS NOT NULL AND contract_version IS NOT NULL AND contract_fingerprint IS NOT NULL)),
    CHECK ((stage_locator IS NULL AND stage_sha256 IS NULL
            AND stage_source_rows IS NULL AND stage_accepted_rows IS NULL AND stage_rejected_rows IS NULL)
        OR (stage_locator IS NOT NULL AND stage_sha256 IS NOT NULL
            AND stage_source_rows IS NOT NULL AND stage_accepted_rows IS NOT NULL
            AND stage_rejected_rows IS NOT NULL)),
    CHECK (terminal_at_ms IS NULL OR state = 'TERMINAL'),
    CHECK (purge_after_ms IS NULL OR terminal_at_ms IS NOT NULL),
    CHECK (next_attempt_at_ms IS NULL OR state <> 'TERMINAL')
);

CREATE UNIQUE INDEX ux_import_delivery_active_candidate
ON import_delivery(source_id, candidate_token)
WHERE state <> 'TERMINAL';

CREATE INDEX ix_import_delivery_head
ON import_delivery(sequence_no)
WHERE state <> 'TERMINAL';

CREATE INDEX ix_import_delivery_due
ON import_delivery(state, next_attempt_at_ms, sequence_no)
WHERE state <> 'TERMINAL';

CREATE INDEX ix_import_delivery_source_state_age
ON import_delivery(source_id, state, updated_at_ms, sequence_no);

CREATE INDEX ix_import_delivery_terminal_purge
ON import_delivery(purge_after_ms, sequence_no)
WHERE state = 'TERMINAL';

CREATE TABLE import_delivery_transition (
    delivery_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal > 0),
    from_state TEXT NOT NULL,
    to_state TEXT NOT NULL,
    safe_code TEXT,
    occurred_at_ms INTEGER NOT NULL CHECK (occurred_at_ms >= 0),
    PRIMARY KEY (delivery_id, ordinal),
    FOREIGN KEY (delivery_id) REFERENCES import_delivery(delivery_id) ON DELETE CASCADE
);

CREATE INDEX ix_import_delivery_transition_time
ON import_delivery_transition(occurred_at_ms, delivery_id, ordinal);
