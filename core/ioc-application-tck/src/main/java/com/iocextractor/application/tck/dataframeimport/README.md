# com.iocextractor.application.tck.dataframeimport

## Responsibility and sources

Reusable managed-import persistence baselines. [package-info.java](package-info.java)
marks this contract namespace; application ports/values and JUnit/AssertJ supply
its dependencies, with no JDBC or Spring implementation.

| Source | Factory / fixture |
|---|---|
| [ImportDeliveryLedgerContractTest](ImportDeliveryLedgerContractTest.java) | `createLedger()` returns a clean delivery ledger |
| [CanonicalImportWriterContractTest](CanonicalImportWriterContractTest.java) | `createFixture()` returns writer, valid nonempty sealed command and a durable-effect assertion |

## Delivery ledger baseline

The ledger base checks reservation identity/replay, active-candidate deduplication,
strictly increasing global sequence, state/version CAS and exact snapshot/contract/
sealed-stage checkpoints. Sequence gaps are allowed; no contiguous numbering is
promised. Recovery is ordered and bounded. A deferred global head cannot be
bypassed by later due work; retry attempt counting and replay remain explicit.

Missing/stale transitions return their specified outcomes. Terminal rejection
releases the active candidate for a new occurrence; replay records its cause.
Pre-commit terminal outcomes, forward-only terminal state, versioned purge and
count-only retention are checked. These port scenarios do not exercise transport
ownership, snapshot bytes, SQLite workspace recovery or every concurrent race.

## Canonical writer baseline and its limits

The writer base performs one valid promotion and its exact replay. The first result
is `COMMITTED`, replay is `ALREADY_COMMITTED`, and the remaining receipt result
matches recursively. An adapter-supplied assertion verifies exactly one canonical
effect after both calls. It must inspect relevant persisted rows, lifecycle/alias/
slot/revision and receipt effects; it must not merely count method invocations.

This base is a receipt-idempotency baseline. One valid fixture does not by itself
qualify cross-artifact rollback or every promotion failure point. Those scenarios
belong to the concrete integration matrix alongside the inherited check.

## Consumers and related docs

[JdbcImportDeliveryLedgerContractIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcImportDeliveryLedgerContractIT.java)
uses an isolated migrated service DB. [JdbcCanonicalImportWriterContractIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcCanonicalImportWriterContractIT.java)
uses real dataframe staging/promotion and adds its atomicity/failure matrix.
Consumers own fixture teardown. See [managed import](../../../../../../../../../../docs/dev/dataframe-import.md)
for runtime coordination and [TCK setup](../../../../../../../../README.md) for execution.
