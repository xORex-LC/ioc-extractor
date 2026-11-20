# SQLite dataframe migrations

Canonical dataframe format resources. These tables own business/identity, lifecycle, receipt, registration, projection and export-slot facts.

## Catalog and contents

The owning [DataframeFormatMigrations](../../../../../../../../java/com/iocextractor/adapter/out/store/jdbc/DataframeFormatMigrations.java) explicitly loads these
classpath resources as UTF-8; the directory is not scanned automatically. The
current catalog ends at version 12. A new file requires a matching catalog entry.

| Resource | Purpose |
|---|---|
| [v1__dataframe_format.sql](v1__dataframe_format.sql) | Initial format marker; dataframe_schema_format is historical base-layout metadata |
| [v2__artifact_identity.sql](v2__artifact_identity.sql) | Artifact identity hash/epoch guard |
| [v3__artifact_revision.sql](v3__artifact_revision.sql) | Artifact revision/change-time metadata |
| [v4__canonical_lifecycle_foundation.sql](v4__canonical_lifecycle_foundation.sql) | Lifecycle control/activation, allocators, projection work, observations and complete-receipt foundation |
| [v5__stable_reusable_export_slots.sql](v5__stable_reusable_export_slots.sql) | Profile/artifact lifecycle-slot assignments, free slots and namespace state |
| [v6__bounded_lifecycle_reconciliation_state.sql](v6__bounded_lifecycle_reconciliation_state.sql) | Constant-cardinality lifecycle reconciliation checkpoint, seeded from earlier cycles |
| [v7__canonical_match_aliases.sql](v7__canonical_match_aliases.sql) | Versioned record/match definitions and indexed complete-key lifecycle aliases |
| [v8__coalesced_export_slot_ranges.sql](v8__coalesced_export_slot_ranges.sql) | Convert individual free slots to coalesced ranges and remove the old free-slot table |
| [v9__managed_import_commit.sql](v9__managed_import_commit.sql) | Canonical import commit, artifact evidence, row rejections and slot resolutions |
| [v10__registered_observation_order.sql](v10__registered_observation_order.sql) | Dataframe namespace/order authority and lifecycle/compatibility ordered-field origins |
| [v11__ordered_field_provenance.sql](v11__ordered_field_provenance.sql) | Receipt payload version/field positions, mutation counters and archived ordered origins |
| [v12__accepted_import_warnings.sql](v12__accepted_import_warnings.sql) | Accepted import row-warning evidence |

Per-artifact business, provenance, lifecycle/history and typed receipt-row tables
are additionally planned from configuration by `DataframeSchemaReconciler` and
`LifecycleArtifactSchemaPlanner`; these SQL files are not the complete configured
schema. The historical `dataframe-v1` marker is not the current user_version.
Service delivery counters/checkpoints cannot replace `import_commit` authority.

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
tests. See [the other database family](../service/README.md), the
[production package](../../../../../../../../java/com/iocextractor/adapter/out/store/jdbc/README.md) and
[storage capability](../../../../../../../../../../../../docs/dev/storage.md) for ownership/recovery.
