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
| `source_read` | source | Source document text was read. |
| `artifact_write` | sink | CSV artifact was written. |
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

| Field | Namespace | Description |
|---|---|---|
| `event.action` | ecs | Stable machine-readable action of the event. |
| `event.type` | ecs | ECS event type classification. |
| `event.outcome` | ecs | Stable success, failure, or unknown outcome. |
| `event.duration` | ecs | Event duration in nanoseconds. |
| `file.path` | ecs | Input or output file path. |
| `ioc.run.id` | ioc | Unique pipeline run identifier. |
| `ioc.source.id` | ioc | Logical source identifier. |
| `ioc.mode` | ioc | Runtime mode: oneshot or daemon. |
| `ioc.stage` | ioc | Current pipeline stage identifier. |
| `ioc.source.path` | ioc | Normalized source document path. |
| `ioc.source.content_hash` | ioc | Content hash of the source document. |
| `ioc.artifact.name` | ioc | Configured artifact name. |
| `ioc.rows` | ioc | Number of rows in the operation. |
| `ioc.db.role` | ioc | Logical database role. |
| `ioc.schema.version` | ioc | Observed database schema version. |
| `ioc.migration.version` | ioc | Schema migration version. |
| `ioc.identity.epoch` | ioc | Artifact identity epoch. |
| `ioc.affected_rows` | ioc | Number of rows affected by an operation. |
| `ioc.export.profile` | ioc | Configured immutable export profile. |
| `ioc.export.slice.id` | ioc | Immutable export slice identifier. |
| `ioc.export.revision` | ioc | Maximum canonical revision covered by an export slice. |
| `ioc.event.id` | ioc | Stable control-event identifier. |
| `ioc.event.type` | ioc | Stable project control-event type. |
| `ioc.event.version` | ioc | Control-event payload contract version. |
| `ioc.event.correlation_id` | ioc | Control-event correlation identifier. |
| `ioc.event.causation_id` | ioc | Event or command identifier that caused this event. |
| `ioc.event.handler` | ioc | Local control-event handler name. |
| `ioc.sync.endpoint` | ioc | Logical sync endpoint name without transport secrets. |
| `ioc.sync.files` | ioc | Number of files in the sync operation. |
| `ioc.sync.target` | ioc | Logical publish target name. |
| `ioc.sync.key` | ioc | Key used to serialize sync work. |
| `ioc.sync.queue_depth` | ioc | Number of queued sync work items. |
| `ioc.sync.running` | ioc | Number of active sync work items. |
| `ioc.sync.oldest_age` | ioc | Age of the oldest pending sync work item. |
| `ioc.sync.shed_to_reconcile` | ioc | Whether work was shed to periodic reconciliation. |
| `ioc.sync.abandoned_work` | ioc | Number of abandoned sync work items. |
| `ioc.diagnostic.code` | ioc | Stable diagnostic code. |
| `ioc.diagnostic.category` | ioc | Diagnostic processing category. |
| `ioc.diagnostic.severity` | ioc | Diagnostic severity independent of log level. |
