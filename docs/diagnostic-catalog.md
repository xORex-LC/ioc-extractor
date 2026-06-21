# Diagnostic Catalog

Generated from `DiagnosticCatalogs`.

| Code | Category | Severity | Message Key | Default Template |
|---|---|---|---|---|
| `CONFIG.INVALID_PROPERTY` | CONFIG | FATAL | `config.invalid-property` | Invalid configuration property {property}: {reason} |
| `CONFIG.UNKNOWN_POLICY` | CONFIG | FATAL | `config.unknown-policy` | Unknown policy {policy}: {reason} |
| `SOURCE.READ_FAILED` | SOURCE | FATAL | `source.read-failed` | Source {source} could not be read: {reason} |
| `SOURCE.UNSUPPORTED_FORMAT` | SOURCE | ERROR | `source.unsupported-format` | Source {source} has unsupported format {format} |
| `SOURCE.EMPTY_TEXT` | SOURCE | WARN | `source.empty-text` | Source {source} produced empty text |
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
| `PIPELINE.STAGE_FAILED` | PIPELINE | ERROR | `pipeline.stage-failed` | Pipeline stage {stage} failed: {reason} |
| `PIPELINE.ITEM_SKIPPED` | PIPELINE | WARN | `pipeline.item-skipped` | Pipeline item {item} was skipped at stage {stage}: {reason} |
