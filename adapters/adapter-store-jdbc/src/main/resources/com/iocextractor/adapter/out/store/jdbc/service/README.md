# SQLite service migrations

Durable coordination format resources. These tables own service checkpoints and recovery references, while canonical receipts/ranks remain in dataframe storage.

## Catalog and contents

The owning [ServiceSchemaMigrations](../../../../../../../../java/com/iocextractor/adapter/out/store/jdbc/ServiceSchemaMigrations.java) explicitly loads these
classpath resources as UTF-8; the directory is not scanned automatically. The
current catalog ends at version 14. A new file requires a matching catalog entry.

| Resource | Purpose |
|---|---|
| [v1__service_schema.sql](v1__service_schema.sql) | Initial ingestion ledger, former partition child and legacy-file-import tracking |
| [v2__run_ledger.sql](v2__run_ledger.sql) | Historical aggregation/export run tables |
| [v3__drop_legacy_ingestion_child_table.sql](v3__drop_legacy_ingestion_child_table.sql) | Remove the legacy ingestion_partition child table |
| [v4__ingest_run_ledger.sql](v4__ingest_run_ledger.sql) | Rename aggregation_run to ingest_run and retire the earlier export_run format |
| [v5__export_state.sql](v5__export_state.sql) | Immutable export run/progress state and historical global active-run constraint |
| [v6__sync_ledgers.sql](v6__sync_ledgers.sql) | Remote-fetch identity and per-slice/per-target publish ledgers |
| [v7__publish_reconcile_index.sql](v7__publish_reconcile_index.sql) | Publish profile/slice-name reconciliation index |
| [v8__ingestion_observation_identity.sql](v8__ingestion_observation_identity.sql) | Move ingestion identity from content key to observation ID, preserving legacy rows |
| [v9__managed_dataframe_import.sql](v9__managed_dataframe_import.sql) | Ordered import delivery/state-version CAS and compact transition evidence |
| [v10__document_admission_journal.sql](v10__document_admission_journal.sql) | Document admission and cross-store observation-admission recovery references |
| [v11__import_observation_reservation.sql](v11__import_observation_reservation.sql) | Import observation reservation marker |
| [v12__document_processing_policy.sql](v12__document_processing_policy.sql) | Pinned document processing policy and unfinalized-admission lookup |
| [v13__export_profile_single_flight.sql](v13__export_profile_single_flight.sql) | Replace global export single-flight with per-profile active-run uniqueness |
| [v14__document_execution_admission.sql](v14__document_execution_admission.sql) | Durable document execution attempts, retry deadline/failure and ordered lookup |

Historical table names describe upgrade steps, not supported current aggregation
or partition-staging features. Keep the chain so older supported DBs can advance.
The nullable `import_delivery.public_mutations` column is not populated/read by
the current delivery ledger; canonical mutation evidence is dataframe-owned.
The remote-fetch key still lacks endpoint/source namespace isolation (OPS-11).

## Execution and failure boundaries

[SqliteUserVersionSchemaMigrator](../../../../../../../../java/com/iocextractor/adapter/out/store/jdbc/SqliteUserVersionSchemaMigrator.java)
requires contiguous versions from one, skips applied versions and refuses a
database newer than its target. Each version's SQL and user_version advance are
intended to commit together; separate versions and the two database roles do not
form one transaction. SQL failures have a rollback path. Unchecked/Error exits
currently have a demonstrated partial-DDL gap:
[OPS-15](../../../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-15). A failed migration
must not be assumed to have left schema/version unchanged.

Scripts use a simple semicolon splitter after line-comment removal. Do not add
trigger/BEGIN-END blocks or semicolons inside literals without replacing that
parser and its tests. Applied SQL is upgrade history: append a migration rather
than editing an old script to repair an existing database. The migrator stores
user_version, not historical script checksums; it cannot detect such edits.
Never lower user_version to force an incompatible binary to open a database.

The [migration suite](../../../../../../../../../test/java/com/iocextractor/adapter/out/store/jdbc/SqliteUserVersionSchemaMigratorIT.java)
checks ordered application, current-version skip, supported upgrade preservation,
SQL rollback and downgrade refusal. It does not currently cover OPS-15's
unchecked/Error rollback gap. Configured artifact reconciliation has its own
tests. See [the other database family](../dataframe/README.md), the
[production package](../../../../../../../../java/com/iocextractor/adapter/out/store/jdbc/README.md) and
[storage capability](../../../../../../../../../../../../docs/dev/storage.md) for ownership/recovery.
