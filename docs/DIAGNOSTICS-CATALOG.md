# Diagnostic Catalog

Generated from `DiagnosticCatalogs`.

| Code | Category | Severity | Impact | Message Key | Default Template |
|---|---|---|---|---|---|
| `SOURCE.READ_FAILED` | SOURCE | FATAL | RUN | `source.read-failed` | Source {source} could not be read: {reason} |
| `SOURCE.UNSUPPORTED_FORMAT` | SOURCE | ERROR | RUN | `source.unsupported-format` | Source {source} has unsupported format {format} |
| `SOURCE.EMPTY_TEXT` | SOURCE | WARN | RUN | `source.empty-text` | Source {source} produced empty text |
| `SOURCE.MARKERS_UNMATCHED` | SOURCE | WARN | RUN | `source.markers-unmatched` | No section marker matched {unattributed} of {total} indicator(s); source left empty (extend ioc.source.section-markers) |
| `EXTRACTION.INDICATOR_SKIPPED` | EXTRACTION | DEBUG | ELEMENT | `extraction.indicator-skipped` | Indicator {indicator} was skipped: {reason} |
| `EXTRACTION.AMBIGUOUS_VALUE` | EXTRACTION | WARN | ELEMENT | `extraction.ambiguous-value` | Value {value} is ambiguous: {reason} |
| `CLASSIFY.UNSUPPORTED_INDICATOR_TYPE` | CLASSIFY | ERROR | ELEMENT | `classify.unsupported-indicator-type` | Indicator type {type} is not supported by classifier {classifier} |
| `SINK.WRITE_FAILED` | SINK | FATAL | RUN | `sink.write-failed` | Sink {sink} failed to write artifact {artifact}: {reason} |
| `SINK.ROW_MAPPING_FAILED` | SINK | ERROR | ELEMENT | `sink.row-mapping-failed` | Sink {sink} failed to map row for indicator {indicator}: {reason} |
| `SINK.CHARSET_UNMAPPABLE` | SINK | WARN | OPERATION | `sink.charset-unmappable` | Artifact {artifact} projection to {charset} replaced unrepresentable values: {affectedValues} data value(s), {affectedHeaderValues} header value(s) across {affectedRows} row(s) |
| `INGEST.CLAIM_FAILED` | INGEST | FATAL | OPERATION | `ingest.claim-failed` | Source {source} could not be claimed for ingestion: {reason} |
| `INGEST.SOURCE_UNREADABLE` | INGEST | ERROR | OPERATION | `ingest.source-unreadable` | Source {source} could not be read for ingestion identity: {reason} |
| `INGEST.LEDGER_WRITE_FAILED` | INGEST | FATAL | OPERATION | `ingest.ledger-write-failed` | Ingestion ledger update failed for source {source}: {reason} |
| `INGEST.DEAD_LETTER_FAILED` | INGEST | ERROR | OPERATION | `ingest.dead-letter-failed` | Source {source} could not be moved to the failed area: {reason} |
| `INGEST.STATE_TRANSITION_CONFLICT` | INGEST | ERROR | OPERATION | `ingest.state-transition-conflict` | Ingestion ledger operation {operation} for source {source} returned {transition}; expected {expected} |
| `INGEST.RECOVERY_FAILED` | INGEST | ERROR | OPERATION | `ingest.recovery-failed` | Ingestion recovery failed for source {source}: {reason} |
| `IMPORT.CLAIM_FAILED` | IMPORT | ERROR | OPERATION | `import.claim-failed` | Managed import claim failed; durable retry remains scheduled |
| `IMPORT.SOURCE_NOT_CONFIGURED` | IMPORT | ERROR | OPERATION | `import.source-not-configured` | Managed import delivery references no configured source |
| `IMPORT.SOURCE_CAPABILITY_FAILED` | IMPORT | ERROR | OPERATION | `import.source-capability-failed` | Managed import source capability is temporarily unavailable; intake remains closed |
| `IMPORT.SOURCE_NAMESPACE_INCOMPATIBLE` | IMPORT | FATAL | OPERATION | `import.source-namespace-incompatible` | Managed import source namespace or permissions are incompatible; intake remains closed |
| `IMPORT.INPUT_INVALID` | IMPORT | ERROR | OPERATION | `import.input-invalid` | Managed import input failed its declared structural contract |
| `IMPORT.CONTRACT_NOT_RECOGNIZED` | IMPORT | ERROR | OPERATION | `import.contract-not-recognized` | Managed import input matched no allowlisted contract |
| `IMPORT.CONTRACT_AMBIGUOUS` | IMPORT | ERROR | OPERATION | `import.contract-ambiguous` | Managed import input matched more than one allowlisted contract |
| `IMPORT.LIMIT_EXCEEDED` | IMPORT | ERROR | OPERATION | `import.limit-exceeded` | Managed import input exceeded a configured hard resource limit |
| `IMPORT.CAPACITY_PAUSED` | IMPORT | WARN | OPERATION | `import.capacity-paused` | Managed import intake is safely paused at a configured capacity watermark |
| `IMPORT.PROCESSING_FAILED` | IMPORT | ERROR | OPERATION | `import.processing-failed` | Managed import processing failed; durable retry remains scheduled |
| `IMPORT.FINALIZATION_FAILED` | IMPORT | ERROR | OPERATION | `import.finalization-failed` | Managed import finalization failed; forward recovery remains scheduled |
| `IMPORT.CONSISTENCY_FAILED` | IMPORT | FATAL | OPERATION | `import.consistency-failed` | Managed import durable evidence is contradictory and intake remains closed |
| `IMPORT.RETENTION_FAILED` | IMPORT | ERROR | OPERATION | `import.retention-failed` | Managed import terminal retention failed and will be retried |
| `IMPORT.RETENTION_CONTRADICTION` | IMPORT | FATAL | OPERATION | `import.retention-contradiction` | Managed import terminal source evidence is contradictory; cleanup remains closed |
| `IMPORT.CHANGE_SIGNAL_FAILED` | IMPORT | WARN | OPERATION | `import.change-signal-failed` | Managed import change notification is unavailable; polling remains active |
| `STORAGE.MIGRATION_APPLIED` | STORAGE | INFO | OPERATION | `storage.migration-applied` | Storage {dbRole} applied schema migration {migrationVersion}; schema is now {schemaVersion} |
| `STORAGE.MIGRATION_ROLLBACK` | STORAGE | FATAL | OPERATION | `storage.migration-rollback` | Storage {dbRole} rolled back schema migration {migrationVersion}: {reason} |
| `STORAGE.MIGRATION_DOWNGRADE` | STORAGE | FATAL | OPERATION | `storage.migration-downgrade` | Storage {dbRole} schema version {fromVersion} is newer than supported version {toVersion} |
| `STORAGE.IMPORT_PARTIAL` | STORAGE | ERROR | OPERATION | `storage.import-partial` | Storage import {importName} from {sourcePath} stopped before completion: {reason} |
| `STORAGE.IMPORT_IDEMPOTENT_REPLAY` | STORAGE | INFO | OPERATION | `storage.import-idempotent-replay` | Storage import {importName} replay skipped already completed source {sourcePath} |
| `STORAGE.IDENTITY_DRIFT` | STORAGE | FATAL | OPERATION | `storage.identity-drift` | Artifact {artifact} identity drifted at epoch {identityEpoch}: {reason} |
| `STORAGE.IDENTITY_EPOCH_BUMP` | STORAGE | INFO | OPERATION | `storage.identity-epoch-bump` | Artifact {artifact} identity epoch bumped from {fromEpoch} to {toEpoch} |
| `STORAGE.SCHEMA_ADDED` | STORAGE | INFO | OPERATION | `storage.schema-added` | Artifact {artifact} schema added column {column} |
| `STORAGE.SCHEMA_DESTRUCTIVE_CHANGE` | STORAGE | FATAL | OPERATION | `storage.schema-destructive-change` | Artifact {artifact} schema has destructive change {change}: {reason} |
| `EXPORT.UNSUPPORTED_MODE` | EXPORT | FATAL | OPERATION | `export.unsupported-mode` | Export profile {profile} requests unsupported output mode {mode} |
| `EXPORT.SNAPSHOT_READ_FAILED` | EXPORT | ERROR | OPERATION | `export.snapshot-read-failed` | Export profile {profile} snapshot could not be read: {reason} |
| `EXPORT.SLICE_WRITE_FAILED` | EXPORT | ERROR | OPERATION | `export.slice-write-failed` | Export run {runId} could not write slice at {path}: {reason} |
| `EXPORT.MANIFEST_INVALID` | EXPORT | ERROR | OPERATION | `export.manifest-invalid` | Export run {runId} has an invalid slice at {path}: {reason} |
| `EXPORT.ATOMIC_PUBLISH_UNSUPPORTED` | EXPORT | FATAL | OPERATION | `export.atomic-publish-unsupported` | Export run {runId} cannot atomically publish {path}: {reason} |
| `EXPORT.STATE_TRANSITION_CONFLICT` | EXPORT | ERROR | OPERATION | `export.state-transition-conflict` | Export run {runId} cannot transition from {actualStatus} to {nextStatus}; expected {expectedStatus} |
| `EXPORT.RECOVERY_FAILED` | EXPORT | ERROR | OPERATION | `export.recovery-failed` | Export run {runId} could not recover from {status}: {reason} |
| `SYNC.ENDPOINT_UNREACHABLE` | SYNC | ERROR | OPERATION | `sync.endpoint-unreachable` | Sync endpoint {endpoint} is unreachable: {reason} |
| `SYNC.AUTH_FAILED` | SYNC | FATAL | OPERATION | `sync.auth-failed` | Sync endpoint {endpoint} rejected credentials: {reason} |
| `SYNC.PERMISSION_DENIED` | SYNC | ERROR | OPERATION | `sync.permission-denied` | Sync endpoint {endpoint} denied access to {path}: {reason} |
| `SYNC.REMOTE_NOT_FOUND` | SYNC | WARN | OPERATION | `sync.remote-not-found` | Remote path {path} was not found on sync endpoint {endpoint} |
| `SYNC.TRANSPORT_TRANSIENT` | SYNC | WARN | OPERATION | `sync.transport-transient` | Transient transport failure on sync endpoint {endpoint}: {reason} |
| `SYNC.PUBLISH_VERIFY_FAILED` | SYNC | ERROR | OPERATION | `sync.publish-verify-failed` | Published slice {sliceId} for target {targetId} failed verification: {reason} |
| `SYNC.LOCAL_SLICE_INVALID` | SYNC | ERROR | OPERATION | `sync.local-slice-invalid` | Local export slice {profile}/{sliceName} is invalid and was skipped: {reason} |
| `LIFECYCLE.ADMISSION_FAILED` | LIFECYCLE | FATAL | OPERATION | `lifecycle.admission-failed` | Canonical lifecycle admission failed: {reason} |
| `LIFECYCLE.CLOCK_UNSAFE` | LIFECYCLE | FATAL | OPERATION | `lifecycle.clock-unsafe` | System UTC clock cannot establish a safe lifecycle time: {reason} |
| `LIFECYCLE.POLICY_MISMATCH` | LIFECYCLE | FATAL | OPERATION | `lifecycle.policy-mismatch` | Canonical lifecycle policy conflicts with durable activation state: {reason} |
| `LIFECYCLE.RECONCILIATION_FAILED` | LIFECYCLE | ERROR | OPERATION | `lifecycle.reconciliation-failed` | Canonical expiration reconciliation failed: {reason} |
| `LIFECYCLE.PROJECTION_FAILED` | LIFECYCLE | ERROR | OPERATION | `lifecycle.projection-failed` | Mutable artifact projection convergence failed: {reason} |
| `LIFECYCLE.HISTORY_RETENTION_FAILED` | LIFECYCLE | ERROR | OPERATION | `lifecycle.history-retention-failed` | Lifecycle history retention failed: {reason} |
| `PIPELINE.STAGE_FAILED` | PIPELINE | ERROR | RUN | `pipeline.stage-failed` | Pipeline stage {stage} failed: {reason} |
| `PIPELINE.DIAGNOSTICS_SUPPRESSED` | PIPELINE | WARN | RUN | `pipeline.diagnostics-suppressed` | Suppressed {suppressedCount} diagnostics after reaching the run budget of {limit} |
| `PIPELINE.ITEM_SKIPPED` | PIPELINE | WARN | ELEMENT | `pipeline.item-skipped` | Pipeline item {item} was skipped at stage {stage}: {reason} |
