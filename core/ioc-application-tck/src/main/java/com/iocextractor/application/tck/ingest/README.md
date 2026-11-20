# com.iocextractor.application.tck.ingest

## Responsibility and type

[IngestionLedgerContractTest](IngestionLedgerContractTest.java) is the common
`IngestionLedger` baseline. It depends on application values/port, JDK path/time/
coordination primitives and JUnit/AssertJ, not filesystem or JDBC implementations.

## Fixture and exercised behavior

Implement `createLedger(Clock)` with a clean ledger honoring the supplied clock.
The base supplies no resource-cleanup hook; the concrete suite owns resources
and teardown. Synthetic `Path` values describe source/processing/archive locations;
they do not cause real file moves in the TCK.

The base checks claim/archive persistence and timestamps, recoverable-only listing,
missing-record outcomes, failed-record creation/context preservation, idempotent
replay and terminal monotonicity. A failed occurrence cannot be archived or lose its
first failure evidence. Distinct observation IDs can represent repeated deliveries
of the same content; content identity alone must not suppress the later occurrence.

A coordinated archive-versus-fail race requires exactly one applied transition and
one conflict. This exercises one terminal race through the supplied instance; it
is not general multi-process locking, crash durability or filesystem-claim evidence.

## Consumers and verification

[FileIngestionLedgerIT](../../../../../../../../../../adapters/adapter-ingest/src/test/java/com/iocextractor/adapter/in/ingest/FileIngestionLedgerIT.java)
and [JdbcIngestionLedgerIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcIngestionLedgerIT.java)
run the inherited tests on actual isolated stores and add backend cases. Both are
Failsafe integration suites. See [ingestion](../../../../../../../../../../docs/dev/ingestion.md) for daemon
orchestration and [TCK setup](../../../../../../../../README.md) for running consumers.
