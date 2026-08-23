# com.iocextractor.application.dataframeimport

Framework-free application model for managed import of structured dataframe
deliveries. P0 defines configuration compilation, behavior fingerprints,
tri-state cells, merge policies and recovery state. It deliberately performs no
filesystem, CSV, Spring, SMB or JDBC work.

| Subpackage | Responsibility |
|---|---|
| `model/` | Delivery, snapshot, stage and policy value objects |
| `contract/` | Collect-all source-contract compiler and immutable catalog |
| `mapping/` | Pure field-level tri-state merge decisions |

Driving and driven boundaries are under
`application.port.{in,out}.dataframeimport`. Later implementation slices may
add orchestration services here, while all integration mechanisms remain in
their adapters.
