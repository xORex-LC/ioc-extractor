# com.iocextractor.application.tck.dataframeimport

Reusable contract tests for managed dataframe-import persistence ports.

| Class | Contract |
|---|---|
| `ImportDeliveryLedgerContractTest` | Idempotent claim reservation, monotonic global order, checkpoint CAS, retry scheduling and ordered recovery |
| `CanonicalImportWriterContractTest` | Receipt-backed idempotency of one cross-artifact canonical transaction |

Adapter test classes subclass these contracts and supply clean fixtures. The
package depends only on `ioc-application`, JUnit and AssertJ; it contains no
JDBC, Spring or adapter implementation.
