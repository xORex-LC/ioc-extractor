# Logging Catalog

Generated from `EventAction` and `LogField`.

## Actions

| Action | Area | When |
|---|---|---|
| `app_start` | app | Application startup completed. |
| `app_stop` | app | Application shutdown started. |
| `command_start` | cli | CLI command execution started. |
| `command_complete` | cli | CLI command execution completed. |
| `stage_start` | pipeline | Pipeline stage execution started. |
| `stage_complete` | pipeline | Pipeline stage execution completed or failed. |
| `pipeline_item_decision` | pipeline | One explicitly enabled per-item pipeline decision was traced. |
| `source_read` | source | Source document text was read. |
| `source_ingest` | source | Source ingestion reached a terminal handled outcome. |
| `ingest_recover` | source | Daemon startup ingestion recovery progressed or failed. |
| `lifecycle_admission` | lifecycle | Canonical lifecycle admission completed or failed. |
| `lifecycle_reconcile` | lifecycle | Canonical expiration reconciliation completed or failed. |
| `lifecycle_projection` | lifecycle | Mutable lifecycle projection convergence completed or failed. |
| `lifecycle_retention` | lifecycle | Bounded lifecycle history retention completed or failed. |
| `lifecycle_clock` | lifecycle | Lifecycle clock confidence changed or became unsafe. |
| `artifact_project` | sink | CSV projection was regenerated from canonical storage. |
| `retention_sweep` | maintenance | Retention sweep evaluated configured targets. |
| `schema_migrate` | storage | Database schema migration progressed or failed. |
| `schema_validate` | storage | Database schema reconciliation evaluated an artifact schema. |
| `db_open` | storage | Database connection was opened. |
| `ledger_import` | storage | Legacy ledger data was imported. |
| `maintenance` | maintenance | Daemon maintenance cycle progressed or failed. |
| `backfill` | maintenance | Backfill operation progressed or failed. |
| `export_start` | export | Export run acquired its durable work slot. |
| `export_complete` | export | Export run reached a terminal checkpoint. |
| `export_slice_write` | export | Export slice files and manifest were written and verified. |
| `export_recover` | export | Incomplete durable export run recovery started. |
| `sync_fetch_start` | sync | Configured remote source fetch started. |
| `sync_fetch_complete` | sync | Configured remote source fetch completed or was isolated as failed. |
| `sync_publish_start` | sync | Configured remote target publish started. |
| `sync_publish_complete` | sync | Configured remote target publish completed or was isolated as failed. |
| `sync_work_admission` | sync | Keyed sync work was admitted or rejected. |
| `sync_work_dispatch` | sync | Keyed sync work was dispatched or completed. |
| `event_publish` | events | Control event publication was attempted. |
| `event_dispatch` | events | Control event dispatch was attempted. |
| `diagnostic_emit` | diagnostics | Processing diagnostic was emitted to the log stream. |

## Fields

| Field | Namespace | JSON type | Description |
|---|---|---|---|
| `event.action` | ecs | `string` | Stable machine-readable action of the event. |
| `event.type` | ecs | `string` | ECS event type classification. |
| `event.outcome` | ecs | `string` | Stable success, failure, or unknown outcome. |
| `event.duration` | ecs | `long` | Event duration in nanoseconds. |
| `error.type` | ecs | `string` | Exception class associated with a failed operation. |
| `file.path` | ecs | `string` | Input or output file path. |
| `ioc.run.id` | ioc | `string` | Unique pipeline run identifier. |
| `ioc.source.id` | ioc | `string` | Logical source identifier. |
| `ioc.mode` | ioc | `string` | Runtime mode: oneshot or daemon. |
| `ioc.stage` | ioc | `string` | Current pipeline stage identifier. |
| `ioc.decision.kind` | ioc | `string` | Pipeline processing step that made a per-item decision. |
| `ioc.decision.outcome` | ioc | `string` | Stable outcome of a per-item pipeline decision. |
| `ioc.decision.rule` | ioc | `string` | Rule or source marker selected by a pipeline decision. |
| `ioc.decision.pattern` | ioc | `string` | Pattern or predicate set evaluated by a pipeline decision. |
| `ioc.decision.result` | ioc | `string` | Compact materialized result of a pipeline decision. |
| `ioc.item.identity` | ioc | `string` | Safe short identity of the item involved in a pipeline decision. |
| `ioc.item.value` | ioc | `string` | TRACE-only item value with query-like data redacted. |
| `ioc.indicator.type` | ioc | `string` | IOC type involved in a pipeline decision. |
| `ioc.span.start` | ioc | `long` | Inclusive source-text offset of a matched item. |
| `ioc.span.end` | ioc | `long` | Exclusive source-text offset of a matched item. |
| `ioc.source.path` | ioc | `string` | Normalized source document path. |
| `ioc.source.content_hash` | ioc | `string` | Content hash of the source document. |
| `ioc.ingest.disposition` | ioc | `string` | Stable terminal disposition of a source ingestion attempt. |
| `ioc.ingest.recovered_runs` | ioc | `long` | Number of incomplete ingest runs examined during startup recovery. |
| `ioc.ingest.recovered_sources` | ioc | `long` | Number of incomplete source records handled during startup recovery. |
| `ioc.lifecycle.state` | ioc | `string` | Persisted canonical lifecycle activation state. |
| `ioc.lifecycle.cycle.id` | ioc | `long` | Durable expiration reconciliation cycle identifier. |
| `ioc.lifecycle.expired` | ioc | `long` | Number of canonical lifecycles expired in an aggregate operation. |
| `ioc.lifecycle.projections` | ioc | `long` | Number of mutable artifact projections converged in an aggregate operation. |
| `ioc.lifecycle.clock.status` | ioc | `string` | Safe lifecycle clock confidence: SAFE, CLAMPED, or UNSAFE. |
| `ioc.lifecycle.clock.skew_ms` | ioc | `long` | Observed backward UTC clock skew in milliseconds. |
| `ioc.lifecycle.due` | ioc | `long` | Aggregate number of physically present canonical rows already due. |
| `ioc.artifact.name` | ioc | `string` | Configured artifact name. |
| `ioc.rows` | ioc | `long` | Number of rows in the operation. |
| `ioc.db.role` | ioc | `string` | Logical database role. |
| `ioc.schema.version` | ioc | `long` | Observed database schema version. |
| `ioc.migration.version` | ioc | `long` | Schema migration version. |
| `ioc.identity.epoch` | ioc | `long` | Artifact identity epoch. |
| `ioc.legacy_import.name` | ioc | `string` | Legacy import source name. |
| `ioc.legacy_import.scanned` | ioc | `long` | Legacy records scanned during import. |
| `ioc.legacy_import.imported` | ioc | `long` | Legacy records imported. |
| `ioc.legacy_import.skipped` | ioc | `long` | Legacy records skipped during import. |
| `ioc.legacy_import.failed` | ioc | `long` | Legacy records that failed import. |
| `ioc.storage.sqlite.tuning` | ioc | `string` | Selected SQLite tuning profile. |
| `ioc.storage.sqlite.max_pool_size` | ioc | `long` | Configured maximum SQLite connection pool size. |
| `ioc.storage.sqlite.write_max` | ioc | `long` | Configured maximum concurrent SQLite writers. |
| `ioc.storage.sqlite.read_max` | ioc | `long` | Configured maximum concurrent SQLite readers. |
| `ioc.storage.sqlite.busy_timeout_ms` | ioc | `long` | Effective SQLite busy timeout in milliseconds. |
| `ioc.export.profile` | ioc | `string` | Configured immutable export profile. |
| `ioc.export.slice.id` | ioc | `string` | Immutable export slice identifier. |
| `ioc.export.revision` | ioc | `long` | Maximum canonical revision covered by an export slice. |
| `ioc.event.id` | ioc | `string` | Stable control-event identifier. |
| `ioc.event.type` | ioc | `string` | Stable project control-event type. |
| `ioc.event.version` | ioc | `long` | Control-event payload contract version. |
| `ioc.event.correlation_id` | ioc | `string` | Control-event correlation identifier. |
| `ioc.event.causation_id` | ioc | `string` | Event or command identifier that caused this event. |
| `ioc.event.handler` | ioc | `string` | Local control-event handler name. |
| `ioc.sync.endpoint` | ioc | `string` | Logical sync endpoint name without transport secrets. |
| `ioc.sync.files` | ioc | `long` | Number of files in the sync operation. |
| `ioc.sync.target` | ioc | `string` | Logical publish target name. |
| `ioc.sync.key` | ioc | `string` | Key used to serialize sync work. |
| `ioc.sync.queue_depth` | ioc | `long` | Number of queued sync work items. |
| `ioc.sync.shed_to_reconcile` | ioc | `boolean` | Whether work was shed to periodic reconciliation. |
| `ioc.sync.abandoned_work` | ioc | `long` | Number of abandoned sync work items. |
| `ioc.completion.status` | ioc | `string` | Typed completion status of a structurally finished processing run. |
| `ioc.diagnostic.total` | ioc | `long` | Total diagnostics observed during the run, including suppressed occurrences. |
| `ioc.diagnostic.suppressed` | ioc | `long` | Diagnostics suppressed by the per-run retention budget. |
| `ioc.diagnostic.count.fatal` | ioc | `long` | FATAL diagnostics observed during the run. |
| `ioc.diagnostic.count.error` | ioc | `long` | ERROR diagnostics observed during the run. |
| `ioc.diagnostic.count.warn` | ioc | `long` | WARN diagnostics observed during the run. |
| `ioc.diagnostic.count.info` | ioc | `long` | INFO diagnostics observed during the run. |
| `ioc.diagnostic.count.debug` | ioc | `long` | DEBUG diagnostics observed during the run. |
| `ioc.diagnostic.count.trace` | ioc | `long` | TRACE diagnostics observed during the run. |
| `ioc.diagnostic.code` | ioc | `string` | Stable diagnostic code. |
| `ioc.diagnostic.category` | ioc | `string` | Diagnostic processing category. |
| `ioc.diagnostic.severity` | ioc | `string` | Diagnostic severity independent of log level. |
