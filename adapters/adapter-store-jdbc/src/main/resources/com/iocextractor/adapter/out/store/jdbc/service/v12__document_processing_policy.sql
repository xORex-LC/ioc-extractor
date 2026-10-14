CREATE TABLE document_processing_policy (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    policy_fingerprint TEXT NOT NULL CHECK (length(policy_fingerprint) = 64)
);

CREATE INDEX ix_document_admission_unfinalized
ON document_admission(registration_finalized)
WHERE registration_finalized = 0;
