CREATE TABLE lifecycle_reconcile_state (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    cycle_sequence INTEGER NOT NULL CHECK (cycle_sequence >= 0),
    cycle_as_of_ms INTEGER,
    state TEXT NOT NULL CHECK (state IN ('NEVER_RUN', 'STARTED', 'COMPLETED', 'FAILED')),
    started_at_ms INTEGER,
    completed_at_ms INTEGER,
    expired_count INTEGER NOT NULL DEFAULT 0 CHECK (expired_count >= 0),
    affected_artifact_count INTEGER NOT NULL DEFAULT 0 CHECK (affected_artifact_count >= 0),
    failure_code TEXT,
    CHECK (
        (state = 'NEVER_RUN'
            AND cycle_sequence = 0
            AND cycle_as_of_ms IS NULL
            AND started_at_ms IS NULL
            AND completed_at_ms IS NULL
            AND failure_code IS NULL)
        OR (state = 'STARTED'
            AND cycle_sequence > 0
            AND cycle_as_of_ms IS NOT NULL
            AND started_at_ms IS NOT NULL
            AND completed_at_ms IS NULL
            AND failure_code IS NULL)
        OR (state = 'COMPLETED'
            AND cycle_sequence > 0
            AND cycle_as_of_ms IS NOT NULL
            AND started_at_ms IS NOT NULL
            AND completed_at_ms IS NOT NULL
            AND failure_code IS NULL)
        OR (state = 'FAILED'
            AND cycle_sequence > 0
            AND cycle_as_of_ms IS NOT NULL
            AND started_at_ms IS NOT NULL
            AND completed_at_ms IS NOT NULL
            AND failure_code IS NOT NULL)
    )
);

INSERT INTO lifecycle_reconcile_state(
    singleton_id, cycle_sequence, cycle_as_of_ms, state, started_at_ms,
    completed_at_ms, expired_count, affected_artifact_count, failure_code)
SELECT 1, cycle_id, cycle_as_of_ms, state, started_at_ms,
       completed_at_ms, expired_count, affected_artifact_count, failure_code
FROM lifecycle_reconcile_cycle
ORDER BY cycle_id DESC
LIMIT 1;

INSERT OR IGNORE INTO lifecycle_reconcile_state(
    singleton_id, cycle_sequence, state, expired_count, affected_artifact_count)
VALUES (1, 0, 'NEVER_RUN', 0, 0);
