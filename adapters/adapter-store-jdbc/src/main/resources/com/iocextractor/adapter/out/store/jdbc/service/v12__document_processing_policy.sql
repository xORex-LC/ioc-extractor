CREATE TABLE document_processing_policy (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    policy_fingerprint TEXT NOT NULL CHECK (length(policy_fingerprint) = 64)
);
