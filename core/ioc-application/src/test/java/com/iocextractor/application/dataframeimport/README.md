# Managed import service tests

Failure-window, ordering and idempotency decisions over explicit ledger/source/workspace/receipt fakes.

| Entry points / files | Responsibility |
|---|---|
| [DataframeImportAdmissionServiceTest](DataframeImportAdmissionServiceTest.java), [DataframeImportDetectionServiceTest](DataframeImportDetectionServiceTest.java), [DataframeImportSourceReadinessCoordinatorTest](DataframeImportSourceReadinessCoordinatorTest.java) | Reserve-before-claim, complete detection, capability gating and source isolation. |
| [DataframeImportStagingServiceTest](DataframeImportStagingServiceTest.java), [DataframeImportPromotionServiceTest](DataframeImportPromotionServiceTest.java), [DataframeImportProcessingServiceTest](DataframeImportProcessingServiceTest.java) | Recognition/session/seal, atomic-writer hand-off, durable global-head state and post-effect response failures. |
| [DataframeImportRecoveryServiceTest](DataframeImportRecoveryServiceTest.java), [DataframeImportRetentionServiceTest](DataframeImportRetentionServiceTest.java) | Forward recovery and protected transport/local/receipt/ledger cleanup ordering. |
| [EventPublishingCanonicalImportWriterTest](EventPublishingCanonicalImportWriterTest.java), [ResilientDataframeImportObserverTest](ResilientDataframeImportObserverTest.java) | Newly committed hints, replay suppression and operational failure isolation. |
| [contract/README.md](contract/README.md), [mapping/README.md](mapping/README.md), [model/README.md](model/README.md) | Catalog admission, logical row mapping and model invariants. |

A simulated commit receipt proves that orchestration selects forward finalization; actual atomic cross-artifact promotion is a downstream writer/TCK contract. Deterministic fake source ownership does not qualify local filesystem/SMB ACL/rename behavior. Keep those facts distinct when adding a recovery case or reporting completeness.

[Test map](../README.md) owns commands/evidence limits; [TESTING.md](../../../../../../../../../docs/TESTING.md) owns lifecycle and test quality.
