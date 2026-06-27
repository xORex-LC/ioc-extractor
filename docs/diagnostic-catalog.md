# Diagnostic Catalog

Generated from `DiagnosticCatalogs`.

| Code | Category | Severity | Message Key | Default Template |
|---|---|---|---|---|
| `CONFIG.INVALID_PROPERTY` | CONFIG | FATAL | `config.invalid-property` | Invalid configuration property {property}: {reason} |
| `CONFIG.UNKNOWN_POLICY` | CONFIG | FATAL | `config.unknown-policy` | Unknown policy {policy}: {reason} |
| `SOURCE.READ_FAILED` | SOURCE | FATAL | `source.read-failed` | Source {source} could not be read: {reason} |
| `SOURCE.UNSUPPORTED_FORMAT` | SOURCE | ERROR | `source.unsupported-format` | Source {source} has unsupported format {format} |
| `SOURCE.EMPTY_TEXT` | SOURCE | WARN | `source.empty-text` | Source {source} produced empty text |
| `SOURCE.MARKERS_UNMATCHED` | SOURCE | WARN | `source.markers-unmatched` | No section marker matched {unattributed} of {total} indicator(s); source left empty (extend ioc.source.section-markers) |
| `EXTRACTION.PATTERN_INVALID` | EXTRACTION | FATAL | `extraction.pattern-invalid` | Extraction pattern {pattern} is invalid: {reason} |
| `EXTRACTION.INDICATOR_SKIPPED` | EXTRACTION | DEBUG | `extraction.indicator-skipped` | Indicator {indicator} was skipped: {reason} |
| `EXTRACTION.AMBIGUOUS_VALUE` | EXTRACTION | WARN | `extraction.ambiguous-value` | Value {value} is ambiguous: {reason} |
| `CLASSIFY.AMBIGUOUS_MATCH` | CLASSIFY | WARN | `classify.ambiguous-match` | Indicator {indicator} matched multiple classification rules: {candidates} |
| `CLASSIFY.UNSUPPORTED_INDICATOR_TYPE` | CLASSIFY | ERROR | `classify.unsupported-indicator-type` | Indicator type {type} is not supported by classifier {classifier} |
| `SINK.WRITE_FAILED` | SINK | FATAL | `sink.write-failed` | Sink {sink} failed to write artifact {artifact}: {reason} |
| `SINK.ROW_MAPPING_FAILED` | SINK | ERROR | `sink.row-mapping-failed` | Sink {sink} failed to map row for indicator {indicator}: {reason} |
| `INGEST.CLAIM_FAILED` | INGEST | FATAL | `ingest.claim-failed` | Source {source} could not be claimed for ingestion: {reason} |
| `INGEST.LEDGER_WRITE_FAILED` | INGEST | FATAL | `ingest.ledger-write-failed` | Ingestion ledger update failed for source {source}: {reason} |
| `INGEST.RECOVERY_FAILED` | INGEST | ERROR | `ingest.recovery-failed` | Ingestion recovery failed for source {source}: {reason} |
| `STORAGE.MIGRATION_APPLIED` | STORAGE | INFO | `storage.migration-applied` | Storage {dbRole} applied schema migration {migrationVersion}; schema is now {schemaVersion} |
| `STORAGE.MIGRATION_ROLLBACK` | STORAGE | FATAL | `storage.migration-rollback` | Storage {dbRole} rolled back schema migration {migrationVersion}: {reason} |
| `STORAGE.MIGRATION_DOWNGRADE` | STORAGE | FATAL | `storage.migration-downgrade` | Storage {dbRole} schema version {fromVersion} is newer than supported version {toVersion} |
| `STORAGE.IMPORT_PARTIAL` | STORAGE | ERROR | `storage.import-partial` | Storage import {importName} from {sourcePath} stopped before completion: {reason} |
| `STORAGE.IMPORT_IDEMPOTENT_REPLAY` | STORAGE | INFO | `storage.import-idempotent-replay` | Storage import {importName} replay skipped already completed source {sourcePath} |
| `STORAGE.IDENTITY_DRIFT` | STORAGE | FATAL | `storage.identity-drift` | Artifact {artifact} identity drifted at epoch {identityEpoch}: {reason} |
| `STORAGE.IDENTITY_EPOCH_BUMP` | STORAGE | INFO | `storage.identity-epoch-bump` | Artifact {artifact} identity epoch bumped from {fromEpoch} to {toEpoch} |
| `STORAGE.SCHEMA_ADDED` | STORAGE | INFO | `storage.schema-added` | Artifact {artifact} schema added column {column} |
| `STORAGE.SCHEMA_DESTRUCTIVE_CHANGE` | STORAGE | FATAL | `storage.schema-destructive-change` | Artifact {artifact} schema has destructive change {change}: {reason} |
| `EXPORT.SNAPSHOT_READ_FAILED` | EXPORT | ERROR | `export.snapshot-read-failed` | Export profile {profile} snapshot could not be read: {reason} |
| `EXPORT.STATE_TRANSITION_CONFLICT` | EXPORT | ERROR | `export.state-transition-conflict` | Export run {runId} cannot transition from {actualStatus} to {nextStatus}; expected {expectedStatus} |
| `PIPELINE.STAGE_FAILED` | PIPELINE | ERROR | `pipeline.stage-failed` | Pipeline stage {stage} failed: {reason} |
| `PIPELINE.ITEM_SKIPPED` | PIPELINE | WARN | `pipeline.item-skipped` | Pipeline item {item} was skipped at stage {stage}: {reason} |
