# Lifecycle application tests

Explicit-time/model tests and fake-backed runtime/projection orchestration.

| Entry points / files | Responsibility |
|---|---|
| [FixedRecordValidityPolicyTest](FixedRecordValidityPolicyTest.java), [RecordLifecycleTest](RecordLifecycleTest.java), [LifecycleContractModelsTest](LifecycleContractModelsTest.java) | Half-open deadlines, constructor boundaries, typed identities/control/result invariants. |
| [LifecycleRuntimeServicesTest](LifecycleRuntimeServicesTest.java) | One-way activation, admission/failure, bounded expiry/history, receipt replay and projection convergence. |
| [GenerationOwnedArtifactProjectionTest](GenerationOwnedArtifactProjectionTest.java) | Per-artifact serialization, installed-generation acknowledgement, retained warnings and failed installation. |

Use controlled effective time and bounded coordination rather than wall-clock guesses. These tests exercise the application contract, not real SQLite locking, WAL, native schema migration, load or an installed timer. JDBC adapter/bootstrap tests supply those separate layers. Failed projection after canonical commit is forward convergence work, not proof that canonical rows were rolled back.

[Test map](../../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
