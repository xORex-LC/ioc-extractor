# com.iocextractor.diagnostics.codes

## Responsibility and sources

Subsystem-owned IOC code enums. [package-info.java](package-info.java) records the
catalog boundary. Enums depend on product metadata and shared API severity/impact;
they do not render, emit, enforce a failure policy or perform the reported operation.

| Source | Owning conditions |
|---|---|
| [SourceDiagnosticCodes](SourceDiagnosticCodes.java) | Source reading, format/text and unmatched attribution markers |
| [ExtractionDiagnosticCodes](ExtractionDiagnosticCodes.java) | Skipped or ambiguous extracted values |
| [ClassificationDiagnosticCodes](ClassificationDiagnosticCodes.java) | Unsupported classification input |
| [SinkDiagnosticCodes](SinkDiagnosticCodes.java) | Canonical/preparation failures and projection charset replacement |
| [IngestDiagnosticCodes](IngestDiagnosticCodes.java) | Claim, ledger, terminal handling and recovery |
| [ImportDiagnosticCodes](ImportDiagnosticCodes.java) | Managed-import admission, processing, consistency, retention and change hints |
| [StorageDiagnosticCodes](StorageDiagnosticCodes.java) | Migration, storage import/replay and identity |
| [SchemaDiagnosticCodes](SchemaDiagnosticCodes.java) | Schema reconciliation; codes also belong to category/prefix `STORAGE` |
| [ExportDiagnosticCodes](ExportDiagnosticCodes.java) | Immutable slice formation and formation-ledger recovery |
| [SyncDiagnosticCodes](SyncDiagnosticCodes.java) | Remote fetch/publish transport, security and slice verification |
| [LifecycleDiagnosticCodes](LifecycleDiagnosticCodes.java) | Admission, clock safety, policy and convergence |
| [PipelineDiagnosticCodes](PipelineDiagnosticCodes.java) | Stage/routing/view failures and bounded-detail suppression |

## IDs, metadata and maintenance

Concrete enums supply stable `CATEGORY.CONSTANT_NAME` IDs, default severity,
impact, unique message key and English fallback template. Treat renaming an enum
constant as an ID compatibility change: IDs can appear in logs and durable state.
Full per-code values belong to the [generated catalog](../../../../../../../../../docs/DIAGNOSTICS-CATALOG.md),
not a second hand-maintained README table.

Producers normally import their subsystem enum directly to create a shared
`Diagnostic`. Rendering/emission code consumes contracts and inspects IOC metadata
only when present. Severity defaults can be overridden on an occurrence; impact
is independent of severity and does not itself decide retry, continuation or writes.
In the shared bounded collector, `OPERATION` occurrences bypass the detail budget;
that is not a global bounded-memory promise for an operation stream.

`ImportDiagnosticCodes.findById` resolves persisted import IDs by exact equality;
null, blank, case changes and unknown IDs return empty. It does not trim text or
serve as a generic decoder for every catalog. Callers retain responsibility for
unknown historical IDs and recovery disposition.

Register a new enum explicitly in `DiagnosticCatalogs`, preserve stable metadata
and run catalog/producer tests. Local registration checks discover enums from
compiled files in this package; they do not prove every production branch emits.
See the [catalog reference](../catalog/README.md) for generation and
[test map](../../../../../../test/java/com/iocextractor/diagnostics/README.md)
for completeness/reference checks.
