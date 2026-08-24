# com.iocextractor.application.port.out.dataframeimport

## Purpose

Narrow driven ports isolating delivery coordination, source ownership, strict
CSV streaming, disk staging, atomic canonical promotion, reports and status.

## Structure

| Port | Responsibility |
|---|---|
| `ImportDeliveryLedger` | Durable sequence, head selection and CAS transitions |
| `ManagedImportSourceLifecycle` | Fail-closed local/SMB ownership and snapshot evidence |
| `ImportChangeSignalSource` | Optional source-level latency hint with no trusted filename payload |
| `DelimitedRecordReader` | Header-only recognition probe plus strict bounded record stream |
| `ImportValueTransformRegistry` | Adapter-neutral access to the validated CSV transform family |
| `ImportWorkspace` | Rebuildable disk-backed staging, capacity state and sealed-stage verification |
| `CanonicalImportWriter` | One cross-artifact dataframe transaction and receipt |
| `ImportReportStore` | Protected atomic source/report terminal unit |
| `ImportStatusReader` | Indexed safe aggregate status |

## Dependencies

**Depends on:** dataframe-import application values and contract definitions.
**Implemented by:** CSV, ingest, SMB and JDBC adapters in later slices. Ports do
not expose Spring, Commons CSV, JDBC, SMBJ or adapter-specific path types.
