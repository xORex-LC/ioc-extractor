# Document ingestion tests

Whole-source occurrence and split preparation/promotion/recovery with explicit collaborators.

| Entry points / files | Responsibility |
|---|---|
| [IngestionServiceTest](IngestionServiceTest.java), [SourceKeyTest](SourceKeyTest.java) | Content versus occurrence identity, duplicate receipt/fallback, failure windows, terminal CAS, archive and diagnostics. |
| [admission/DocumentAdmissionCompletionTest](admission/DocumentAdmissionCompletionTest.java) | Recoverable order/claim/registration terminal handshake and safe cleanup. |

Preparation can finish out of order without changing canonical admission order. Assertions distinguish precommit failure from durable commit/projection recovery and first rejection from already-rejected state. Fake source/ledger behavior is not proof of an actual file/JDBC transaction or cross-process exclusion. Bounded asynchronous fixtures must release their owned workers on failure.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
