# Diagnostic Catalog

Generated from `DiagnosticCatalogs`.

| Code | Category | Severity | Impact | Message Key | Default Template |
|---|---|---|---|---|---|
| `CONFIG.INVALID_PROPERTY` | CONFIG | FATAL | RUN | `config.invalid-property` | Invalid configuration property {property}: {reason} |
| `CONFIG.UNKNOWN_POLICY` | CONFIG | FATAL | RUN | `config.unknown-policy` | Unknown policy {policy}: {reason} |
| `SOURCE.READ_FAILED` | SOURCE | FATAL | RUN | `source.read-failed` | Source {source} could not be read: {reason} |
| `SOURCE.UNSUPPORTED_FORMAT` | SOURCE | ERROR | RUN | `source.unsupported-format` | Source {source} has unsupported format {format} |
| `SOURCE.EMPTY_TEXT` | SOURCE | WARN | RUN | `source.empty-text` | Source {source} produced empty text |
| `SOURCE.MARKERS_UNMATCHED` | SOURCE | WARN | RUN | `source.markers-unmatched` | No section marker matched {unattributed} of {total} indicator(s); source left empty (extend ioc.source.section-markers) |
| `EXTRACTION.PATTERN_INVALID` | EXTRACTION | FATAL | RUN | `extraction.pattern-invalid` | Extraction pattern {pattern} is invalid: {reason} |
| `EXTRACTION.INDICATOR_SKIPPED` | EXTRACTION | DEBUG | ELEMENT | `extraction.indicator-skipped` | Indicator {indicator} was skipped: {reason} |
| `EXTRACTION.AMBIGUOUS_VALUE` | EXTRACTION | WARN | ELEMENT | `extraction.ambiguous-value` | Value {value} is ambiguous: {reason} |
| `CLASSIFY.AMBIGUOUS_MATCH` | CLASSIFY | WARN | ELEMENT | `classify.ambiguous-match` | Indicator {indicator} matched multiple classification rules: {candidates} |
| `CLASSIFY.UNSUPPORTED_INDICATOR_TYPE` | CLASSIFY | ERROR | ELEMENT | `classify.unsupported-indicator-type` | Indicator type {type} is not supported by classifier {classifier} |
| `SINK.WRITE_FAILED` | SINK | FATAL | RUN | `sink.write-failed` | Sink {sink} failed to write artifact {artifact}: {reason} |
| `SINK.ROW_MAPPING_FAILED` | SINK | ERROR | ELEMENT | `sink.row-mapping-failed` | Sink {sink} failed to map row for indicator {indicator}: {reason} |
| `INGEST.CLAIM_FAILED` | INGEST | FATAL | OPERATION | `ingest.claim-failed` | Source {source} could not be claimed for ingestion: {reason} |
| `INGEST.LEDGER_WRITE_FAILED` | INGEST | FATAL | OPERATION | `ingest.ledger-write-failed` | Ingestion ledger update failed for source {source}: {reason} |
| `INGEST.RECOVERY_FAILED` | INGEST | ERROR | OPERATION | `ingest.recovery-failed` | Ingestion recovery failed for source {source}: {reason} |
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
| `SYNC.ENDPOINT_UNKNOWN` | SYNC | FATAL | OPERATION | `sync.endpoint-unknown` | Sync configuration references unknown endpoint {endpoint} from {owner} |
| `SYNC.CREDENTIAL_MISSING` | SYNC | FATAL | OPERATION | `sync.credential-missing` | Sync endpoint {endpoint} is missing required credential {credential} |
| `PIPELINE.STAGE_FAILED` | PIPELINE | ERROR | RUN | `pipeline.stage-failed` | Pipeline stage {stage} failed: {reason} |
| `PIPELINE.DIAGNOSTICS_SUPPRESSED` | PIPELINE | WARN | RUN | `pipeline.diagnostics-suppressed` | Suppressed {suppressedCount} diagnostics after reaching the run budget of {limit} |
| `PIPELINE.ITEM_SKIPPED` | PIPELINE | WARN | ELEMENT | `pipeline.item-skipped` | Pipeline item {item} was skipped at stage {stage}: {reason} |
