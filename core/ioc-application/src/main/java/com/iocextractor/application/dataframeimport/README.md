# Managed dataframe import orchestration

## Purpose and boundary

Compile/recognize structured contracts and advance each owned delivery through pinned snapshots, sealed stage, atomic canonical promotion and forward terminalization.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [contract/README.md](contract/README.md), [mapping/README.md](mapping/README.md), [model/README.md](model/README.md) | Compiled catalog, tri-state mapping and durable delivery vocabulary. |
| [DataframeImportAdmissionService](DataframeImportAdmissionService.java), [DataframeImportDetectionService](DataframeImportDetectionService.java), [DataframeImportSourceReadinessCoordinator](DataframeImportSourceReadinessCoordinator.java) | Ledger-first admission, complete listing and source capability gate. |
| [DataframeImportDetectionCoordinator](DataframeImportDetectionCoordinator.java), [DataframeImportDrainCoordinator](DataframeImportDrainCoordinator.java), [DataframeImportRecoveryCoordinator](DataframeImportRecoveryCoordinator.java) | Coalesced per-source/global-lane hints; durable order remains authority. |
| [DataframeImportStagingService](DataframeImportStagingService.java), [DataframeImportStager](DataframeImportStager.java) | Recognize/map/verify one sealed private stage without canonical writing. |
| [DataframeImportPromotionService](DataframeImportPromotionService.java), [DataframeImportProcessingService](DataframeImportProcessingService.java), [DataframeImportRecoveryService](DataframeImportRecoveryService.java) | Advance only the durable nonterminal head and finalize from committed evidence. |
| [DataframeImportValidationService](DataframeImportValidationService.java), [DataframeImportStatusService](DataframeImportStatusService.java), [DataframeImportReplayService](DataframeImportReplayService.java), [DataframeImportRetentionService](DataframeImportRetentionService.java) | Advisory preview, bounded status, new replay occurrence and ordered cleanup. |
| [EventPublishingCanonicalImportWriter](EventPublishingCanonicalImportWriter.java), [ImportDeliverySnapshotPinned](ImportDeliverySnapshotPinned.java), [ResilientDataframeImportObserver](ResilientDataframeImportObserver.java) | Post-effect hints and isolated operational observation. |

## Contracts and maintenance

Reserve global claim order before transport work, then own exact immutable bytes. Full listing and readiness are independent of notifications. A closed source can be reprobed without blocking unrelated detection; it does not authorize bypassing the durable FIFO head.

Recognition pins exactly one allowed contract. Staging streams through a preparation session, closes that session before seal, and verifies sealed integrity. Source authority, per-row atomicity, duplicate policy and quota accounting apply to processed as well as as-is rows.

The canonical writer atomically commits the accepted delivery across affected dataframe artifacts and a durable receipt. A failed response after commit is not permission to apply again: current ledger/receipt evidence determines forward finalization. Contradictory or impossible backward state fails closed. Operational observer and post-snapshot/postcommit events only accelerate convergence.

Replay is a new causally linked, source-detached occurrence, never reopening terminal state or disposing a producer object again. Retention removes the forward transport terminal object first, then protected local terminal unit/workspace/snapshot/receipt, and ledger CAS last. Shared registration references obey their own safe purge handshake. Validation/status do not grant live claim, reorder, skip or mutation authority.

[Capability internals](../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
