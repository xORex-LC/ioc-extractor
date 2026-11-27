ALTER TABLE ingest_run ADD COLUMN observation_id TEXT;
ALTER TABLE ingest_run ADD COLUMN dataframe_namespace TEXT;
ALTER TABLE ingest_run ADD COLUMN admission_order INTEGER CHECK (admission_order > 0);
ALTER TABLE ingest_run ADD COLUMN policy_fingerprint TEXT;
ALTER TABLE ingest_run ADD COLUMN lifecycle_active INTEGER CHECK (lifecycle_active IN (0, 1));
ALTER TABLE ingest_run ADD COLUMN plan_kind TEXT CHECK (plan_kind IN ('WORKSPACE', 'RECEIPT'));
ALTER TABLE ingest_run ADD COLUMN plan_token TEXT;

CREATE UNIQUE INDEX ux_ingest_run_observation ON ingest_run(observation_id)
WHERE observation_id IS NOT NULL;

CREATE INDEX ix_legacy_ingest_run_recovery ON ingest_run(started_at, run_id)
WHERE observation_id IS NULL AND status IN ('STARTED', 'DB_COMMITTED', 'PROJECTION_COMPLETED');
