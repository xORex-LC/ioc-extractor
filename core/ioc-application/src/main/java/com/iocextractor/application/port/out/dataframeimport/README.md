# com.iocextractor.application.port.out.dataframeimport

## Purpose

Narrow driven ports isolating delivery coordination, source ownership, strict
CSV streaming, disk staging, atomic canonical promotion, reports and status.

## Structure

| Port | Responsibility |
|---|---|
| `ImportDeliveryLedger` | Durable sequence, head selection and CAS transitions |
| `ManagedImportSourceLifecycle` | Fail-closed source detection, claim and forward disposition |
| `ImportSourceCapability` | Value-free positive readiness probe per source |
| `ImportSnapshotStore` / `ImportSnapshotWriter` | Shared immutable local publication, legacy-reference resolution and purge with transport-supplied bytes |
| `ImportTerminalSourceRetention` | Managed-object/outcome-scoped source-remnant purge without path or protocol fields |
| `ImportChangeSignalSource` | Optional source-level latency hint with no trusted filename payload |
| `DelimitedRecordReader` | Header-only recognition probe plus strict bounded record stream |
| `ImportValueTransformRegistry` | Adapter-neutral access to the validated CSV transform family |
| `ImportWorkspace` | Rebuildable disk-backed staging, capacity state and sealed-stage verification |
| `CanonicalImportWriter` | One cross-artifact dataframe transaction, exact receipt replay and safe mutation summary |
| `ProcessedImportRowPreparer` | Ordinary refang/extract/classify/artifact-policy strategy for explicit `processed` contracts |
| `ImportReportStore` | Protected atomic source/report terminal unit |
| `ImportStatusReader` | Indexed safe aggregate status |
| `DataframeImportObserver` | Framework-free operational callbacks after durable delivery checkpoints |

## Dependencies

**Depends on:** dataframe-import application values and contract definitions.
**Implemented by:** CSV, ingest, SMB and JDBC adapters. Ports do not expose
Spring, Commons CSV, JDBC, SMBJ, endpoints, remote paths or protocol statuses.
The snapshot writer uses only the JDK path of a store-owned unpublished local
target and creates no adapter-to-adapter dependency.
