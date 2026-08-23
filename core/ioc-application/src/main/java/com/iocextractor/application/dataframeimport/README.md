# com.iocextractor.application.dataframeimport

Framework-free application model for managed import of structured dataframe
deliveries. P0 defines configuration compilation, behavior fingerprints,
tri-state cells, merge policies and recovery state. P4 adds exact-one
recognition and the streaming recognition-to-sealed-stage orchestration. It
deliberately performs no filesystem, CSV, Spring, SMB or JDBC work.

| Subpackage | Responsibility |
|---|---|
| `model/` | Delivery, snapshot, stage and policy value objects |
| `contract/` | Collect-all source-contract compiler and immutable catalog |
| `mapping/` | Header resolution, declarative row mapping and field-level tri-state merge decisions |

`DataframeImportStagingService` streams each pinned snapshot through the driven
CSV and workspace ports without touching canonical truth. Driving and driven
boundaries are under `application.port.{in,out}.dataframeimport`; all integration
mechanisms remain in their adapters.

`ImportWorkspaceException` classifies safe staging consistency, capacity and
storage failures outside the port namespace, preserving the ports-as-interfaces
boundary.
