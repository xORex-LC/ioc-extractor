ALTER TABLE aggregation_run RENAME TO ingest_run;
ALTER TABLE ingest_run ADD COLUMN source_key TEXT;
DROP INDEX IF EXISTS ix_aggregation_run_status;
CREATE INDEX ix_ingest_run_status ON ingest_run(status);

DROP INDEX IF EXISTS ix_export_run_status;
DROP TABLE IF EXISTS export_run;
