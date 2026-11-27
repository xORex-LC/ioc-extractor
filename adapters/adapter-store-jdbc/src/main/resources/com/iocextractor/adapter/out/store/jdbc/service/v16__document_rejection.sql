CREATE TABLE document_admission_v16 (
    occurrence_id TEXT PRIMARY KEY CHECK (length(trim(occurrence_id)) > 0),
    candidate_path TEXT NOT NULL,
    candidate_file_key TEXT,
    candidate_size INTEGER NOT NULL CHECK (candidate_size >= 0),
    candidate_mtime_ns INTEGER NOT NULL,
    claim_path TEXT NOT NULL UNIQUE,
    claimed_file_key TEXT,
    claimed_size INTEGER CHECK (claimed_size >= 0),
    claimed_mtime_ns INTEGER,
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    phase TEXT NOT NULL CHECK (phase IN ('RESERVED', 'ORDERED', 'CLAIMED', 'LINKED', 'TERMINAL')),
    dataframe_namespace TEXT,
    admission_order INTEGER CHECK (admission_order > 0),
    source_key TEXT,
    terminal_outcome TEXT,
    registration_finalized INTEGER NOT NULL DEFAULT 0 CHECK (registration_finalized IN (0, 1)),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= created_at_ms),
    execution_attempts INTEGER NOT NULL DEFAULT 0 CHECK (execution_attempts >= 0),
    retry_after_ms INTEGER NOT NULL DEFAULT 0,
    execution_failure TEXT,
    rejection_code TEXT CHECK (rejection_code IN ('SOURCE_TOO_LARGE', 'SOURCE_HASH_FAILED', 'DOCUMENT_PREPARATION_FAILED')),
    rejection_source TEXT,
    rejection_target TEXT,
    rejection_file_key TEXT,
    rejection_size INTEGER CHECK (rejection_size >= 0),
    rejection_mtime_ns INTEGER,
    CHECK ((rejection_code IS NULL) = (rejection_source IS NULL)),
    CHECK ((rejection_code IS NULL) = (rejection_target IS NULL)),
    CHECK ((rejection_code IS NULL) = (rejection_file_key IS NULL)),
    CHECK ((rejection_code IS NULL) = (rejection_size IS NULL)),
    CHECK ((rejection_code IS NULL) = (rejection_mtime_ns IS NULL)),
    CHECK (rejection_code IS NULL OR phase IN ('CLAIMED', 'LINKED')
           OR (phase = 'TERMINAL' AND terminal_outcome = 'REJECTED')),
    CHECK ((dataframe_namespace IS NULL) = (admission_order IS NULL)),
    CHECK (phase = 'RESERVED' OR admission_order IS NOT NULL),
    CHECK ((phase = 'TERMINAL') = (terminal_outcome IS NOT NULL)),
    CHECK ((phase IN ('CLAIMED', 'LINKED', 'TERMINAL')) = (claimed_size IS NOT NULL)),
    CHECK (phase <> 'LINKED' OR source_key IS NOT NULL),
    CHECK (source_key IS NULL OR phase IN ('LINKED', 'TERMINAL')),
    CHECK (phase <> 'TERMINAL' OR source_key IS NOT NULL
           OR (terminal_outcome = 'REJECTED' AND rejection_code IS NOT NULL)),
    CHECK (registration_finalized = 0 OR phase = 'TERMINAL')
);

INSERT INTO document_admission_v16 (
    occurrence_id, candidate_path, candidate_file_key, candidate_size, candidate_mtime_ns,
    claim_path, claimed_file_key, claimed_size, claimed_mtime_ns, version, phase,
    dataframe_namespace, admission_order, source_key, terminal_outcome, registration_finalized,
    created_at_ms, updated_at_ms, execution_attempts, retry_after_ms, execution_failure)
SELECT occurrence_id, candidate_path, candidate_file_key, candidate_size, candidate_mtime_ns,
    claim_path, claimed_file_key, claimed_size, claimed_mtime_ns, version, phase,
    dataframe_namespace, admission_order, source_key, terminal_outcome, registration_finalized,
    created_at_ms, updated_at_ms, execution_attempts, retry_after_ms, execution_failure
FROM document_admission;

DROP TABLE document_admission;
ALTER TABLE document_admission_v16 RENAME TO document_admission;

CREATE UNIQUE INDEX ux_document_admission_active_candidate
ON document_admission(candidate_path)
WHERE phase <> 'TERMINAL';

CREATE INDEX ix_document_admission_recovery
ON document_admission(phase, created_at_ms)
WHERE phase <> 'TERMINAL';

CREATE INDEX ix_document_execution_order
ON document_admission(admission_order, created_at_ms, occurrence_id)
WHERE phase <> 'TERMINAL' OR registration_finalized = 0;
