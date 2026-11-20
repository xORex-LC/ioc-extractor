# com.iocextractor.application.tck.export

## Responsibility and type

[ExportRunLedgerContractTest](ExportRunLedgerContractTest.java) exercises formation
ledger state and durable per-artifact progress. It uses application contracts,
IOC `DiagnosticException`, JDK values and JUnit/AssertJ; no SQL or export files.

## Fixture and exercised behavior

`createFixture()` returns `LedgerFixture(ExportRunLedger, ExportProgressStore)`
backed by the same clean store. The base checks the success checkpoints and terminal
visibility, duplicate start identity, refusal of a second active run, CAS/replay,
failure from active checkpoints and `SKIPPED` revision progress.

Terminal state and progress must change atomically. Invalid, empty, duplicate,
wrong-profile or regressing progress cannot leave a partial terminal result.
Replay must preserve persisted progress identity; a changed update timestamp alone
is tolerated, while mismatched profile/artifact/revision/hash/slice/plan evidence
conflicts. Incompatible manifest/terminal replay raises the expected
`EXPORT.STATE_TRANSITION_CONFLICT` diagnostic.

Argument validation and observable rollback scenarios do not prove every physical
concurrent race or storage failure point. Filesystem manifest publication, retention
and delivery are also outside this contract.

## Consumer and related docs

[JdbcExportRunLedgerContractIT](../../../../../../../../../../adapters/adapter-store-jdbc/src/test/java/com/iocextractor/adapter/out/store/jdbc/JdbcExportRunLedgerContractIT.java)
creates both ports over one isolated migrated service DB and closes its datasources.
It adds backward-clock behavior. SQL/migration/concurrency and other recovery cases
remain adapter-owned. See [artifact export](../../../../../../../../../../docs/dev/artifact-export.md) for the
full formation flow and [TCK setup](../../../../../../../../README.md) for execution.
