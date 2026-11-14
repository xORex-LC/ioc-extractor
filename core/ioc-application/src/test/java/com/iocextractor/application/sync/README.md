# Remote coordination and local IO tests

Typed retry/monitor/delivery decisions, plus temporary local staging/landing and marker files.

| Entry points / files | Responsibility |
|---|---|
| [RemoteErrorKindTest](RemoteErrorKindTest.java), [RetrierTest](RetrierTest.java), [SyncDiagnosticReporterTest](SyncDiagnosticReporterTest.java) | Code-owned failure disposition, deterministic retry waits and stable diagnostics. |
| [RemoteSourceMonitorTest](RemoteSourceMonitorTest.java), [RemoteFetchServiceTest](RemoteFetchServiceTest.java) | Complete detection/filtering, detected-batch execution, local landing and current metadata idempotency. |
| [ArtifactPublishServiceTest](ArtifactPublishServiceTest.java) | Ledger pair reconciliation, in-progress/retry/committed-marker outcomes and retention coordination. |
| [CompletedSliceTest](CompletedSliceTest.java), [SyncValueObjectsTest](SyncValueObjectsTest.java) | Verified local-slice metadata and transport-neutral constructor/state contracts. |

Remote file operations are faked; there is no live SMB server, encryption negotiation or target ACL qualification. Temporary NIO success does not establish portable no-replace landing or cross-process fencing. [OPS-11](../../../../../../../../../docs/KNOWN-ISSUES.md#ops-11) and [OPS-12](../../../../../../../../../docs/KNOWN-ISSUES.md#ops-12) remain open even while ordinary local suites pass. Add independent regressions when those contracts are repaired; do not reinterpret repeated polling as a fix for wrong identity.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
