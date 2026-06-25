CREATE TABLE aggregation_run (
    run_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    artifacts TEXT NOT NULL,
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reason TEXT
);

CREATE INDEX ix_aggregation_run_status ON aggregation_run(status);

CREATE TABLE export_run (
    run_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    artifacts TEXT NOT NULL,
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    reason TEXT
);

CREATE INDEX ix_export_run_status ON export_run(status);
