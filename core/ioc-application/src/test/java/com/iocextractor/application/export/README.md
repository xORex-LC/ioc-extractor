# Immutable formation tests

Pure change/format/state models and fake-backed formation, recovery and slice retention.

| Entry points / files | Responsibility |
|---|---|
| [ArtifactSchemaFingerprintTest](ArtifactSchemaFingerprintTest.java), [ExportPlanTest](ExportPlanTest.java), [ExportSnapshotModelTest](ExportSnapshotModelTest.java), [ExportRunTest](ExportRunTest.java) | Ordered schema/format/plan identities, snapshot/manifest and legal run transitions. |
| [ExportChangeDetectorTest](ExportChangeDetectorTest.java) | Plan/revision cheap gating and plan/bytes/coverage redundancy. |
| [ExportServiceTest](ExportServiceTest.java), [ExportRunRecoveryServiceTest](ExportRunRecoveryServiceTest.java) | Effect/checkpoint order, leased single-flight, staged/final crash evidence and post-completion hints. |
| [SliceRetentionServiceTest](SliceRetentionServiceTest.java), [ExportFixtures](ExportFixtures.java) | Whole-slice profile selection, immediate delivery veto and synthetic collaborators. |

Recovery expectations come from ledger/manifest/inspection evidence, never a fresh mutable snapshot. A newer covered revision with identical bytes must still complete a new slice. Fakes test these decisions; independent CSV/JSON wires, real directory rename/marker/hash validation and reusable-slot storage are exercised downstream.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
