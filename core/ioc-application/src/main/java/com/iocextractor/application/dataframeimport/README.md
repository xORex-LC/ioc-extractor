# com.iocextractor.application.dataframeimport

Framework-free application model for managed import of structured dataframe
deliveries. P0 defines configuration compilation, behavior fingerprints,
tri-state cells, merge policies and recovery state. P4 adds exact-one
recognition and streaming recognition-to-sealed-stage orchestration. P5 adds
ledger-first admission, source-keyed complete-listing coordination and the
post-snapshot hint. It deliberately performs no filesystem, CSV, Spring, SMB
or JDBC work.

| Subpackage | Responsibility |
|---|---|
| `model/` | Delivery, snapshot, stage and policy value objects |
| `contract/` | Collect-all source-contract compiler and immutable catalog |
| `mapping/` | Header resolution, declarative row mapping and field-level tri-state merge decisions |

`DataframeImportStagingService` streams each pinned snapshot through the driven
CSV and workspace ports without touching canonical truth. Driving and driven
boundaries are under `application.port.{in,out}.dataframeimport`; all integration
mechanisms remain in their adapters.

`DataframeImportAdmissionService` reserves global order before transport work,
persists retry eligibility instead of sleeping and publishes
`ImportDeliverySnapshotPinned` only after immutable evidence is durable.
`DataframeImportDetectionCoordinator` serializes poll/watch triggers by source;
its full-source reconcile path remains correctness authority.

`ImportWorkspaceException` classifies safe staging consistency, capacity and
storage failures outside the port namespace, preserving the ports-as-interfaces
boundary.
