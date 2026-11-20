# Driving application contracts

## Purpose and boundary

Supported entry operations and their commands/results. CLI, daemon and other driving boundaries supply correlation and scope rather than concrete storage collaborators.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ExtractIocsUseCase](ExtractIocsUseCase.java), [ExtractionCommand](ExtractionCommand.java), [ExtractionResult](ExtractionResult.java), [PreparedExtraction](PreparedExtraction.java) | Extraction summary, optional lifecycle/registration facts and owned split preparation. |
| [ingest/README.md](ingest/README.md), [artifact/lifecycle/README.md](artifact/lifecycle/README.md) | Whole-source processing/recovery and canonical admission/convergence. |
| [dataframeimport/README.md](dataframeimport/README.md), [export/README.md](export/README.md), [sync/README.md](sync/README.md), [maintenance/README.md](maintenance/README.md) | Structured import, immutable formation, remote sync and ordinary retention. |

## Contracts and maintenance

`ExtractionCommand.runId` is supplied by the caller and reused for one recovery chain. Optional lifecycle/registration facts are distinct from content identity. Completion status and exact diagnostics are application outcomes; the CLI owns exit mapping.

`PreparedExtraction` owns sealed precommit state and must close after promotion or cancellation. Only promotion can reserve IDs/write canonical data. Adapter startup must satisfy the capability admission contract; constructing an interface does not itself prepare runtime storage or recover pending work.
[Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
