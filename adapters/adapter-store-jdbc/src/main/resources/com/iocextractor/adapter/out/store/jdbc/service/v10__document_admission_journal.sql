CREATE TABLE document_admission (
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
    CHECK ((dataframe_namespace IS NULL) = (admission_order IS NULL)),
    CHECK (phase = 'RESERVED' OR admission_order IS NOT NULL),
    CHECK ((phase = 'TERMINAL') = (terminal_outcome IS NOT NULL)),
    CHECK ((phase IN ('CLAIMED', 'LINKED', 'TERMINAL')) = (claimed_size IS NOT NULL)),
    CHECK ((phase IN ('LINKED', 'TERMINAL')) = (source_key IS NOT NULL)),
    CHECK (registration_finalized = 0 OR phase = 'TERMINAL')
);

CREATE UNIQUE INDEX ux_document_admission_active_candidate
ON document_admission(candidate_path)
WHERE phase <> 'TERMINAL';

CREATE INDEX ix_document_admission_recovery
ON document_admission(phase, created_at_ms)
WHERE phase <> 'TERMINAL';

CREATE TABLE observation_admission_reference (
    occurrence_id TEXT PRIMARY KEY CHECK (length(trim(occurrence_id)) > 0),
    origin_kind TEXT NOT NULL CHECK (origin_kind IN ('MANAGED_IMPORT')),
    dataframe_namespace TEXT NOT NULL,
    admission_order INTEGER NOT NULL CHECK (admission_order > 0),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    terminal_outcome TEXT,
    registration_finalized INTEGER NOT NULL DEFAULT 0 CHECK (registration_finalized IN (0, 1)),
    created_at_ms INTEGER NOT NULL CHECK (created_at_ms >= 0),
    updated_at_ms INTEGER NOT NULL CHECK (updated_at_ms >= created_at_ms),
    CHECK (registration_finalized = 0 OR terminal_outcome IS NOT NULL)
);

CREATE INDEX ix_observation_admission_reference_recovery
ON observation_admission_reference(terminal_outcome, registration_finalized, created_at_ms);

CREATE INDEX ix_observation_admission_reference_retention
ON observation_admission_reference(registration_finalized, updated_at_ms);
