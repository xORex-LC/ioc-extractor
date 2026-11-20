# Canonical record lifecycle

## Purpose and boundary

Application semantics for fixed validity, one-way activation, bounded expiry/history, confirmation receipts and generation-owned mutable projection.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [RecordValidityPolicy](RecordValidityPolicy.java), [FixedRecordValidityPolicy](FixedRecordValidityPolicy.java), [RecordLifecycle](RecordLifecycle.java), [LifecycleDeadline](LifecycleDeadline.java), [EffectiveTime](EffectiveTime.java) | Half-open active lifecycle and explicit effective UTC time. |
| [LifecycleId](LifecycleId.java), [ObservationId](ObservationId.java), [ConfirmationReceiptId](ConfirmationReceiptId.java), [LifecycleWriteContext](LifecycleWriteContext.java) | Distinct durable lifecycle/delivery/receipt identities and write facts. |
| [LifecycleAdmissionService](LifecycleAdmissionService.java), [CanonicalDataAdmissionState](CanonicalDataAdmissionState.java), [LifecycleActivationService](LifecycleActivationService.java), [LifecycleControlState](LifecycleControlState.java) | Serialized admission and resumable explicit legacy activation. |
| [LifecycleReconciliationService](LifecycleReconciliationService.java), [LifecycleHistoryRetentionService](LifecycleHistoryRetentionService.java) | Bounded expiry cycle and independent history/receipt cleanup. |
| [ConfirmationReceiptReplayService](ConfirmationReceiptReplayService.java), [ConfirmationReceiptSnapshot](ConfirmationReceiptSnapshot.java) | Complete, unexpired, policy-matching replay through the canonical writer. |
| [GenerationOwnedArtifactProjection](GenerationOwnedArtifactProjection.java), [ArtifactProjectionConvergenceService](ArtifactProjectionConvergenceService.java), [ArtifactProjectionState](ArtifactProjectionState.java), [ProjectionAcknowledgement](ProjectionAcknowledgement.java) | Shared snapshot/build/install/ack owner and durable generation backstop. |
| [LifecycleStatusSnapshot](LifecycleStatusSnapshot.java), [LifecycleClockPolicy](LifecycleClockPolicy.java), [LifecycleClockSnapshot](LifecycleClockSnapshot.java) | Safe read models and effective-clock confidence vocabulary. |

## Contracts and maintenance

Active means `validUntil > asOf`; equality is expired. Fixed TTL is per canonical lifecycle. Before-deadline accepted confirmation renews it; later observation starts a new non-reused lifecycle/canonical identity. Public timestamps/export identity are separate contracts, not aliases for internal validity.

Admission resumes activation before opening canonical work and, when active, reconciles/converges durable pending state. Existing-record expiry cutover is explicit and one-way. Expiry changes active membership/projection generation, not insert-driven export revision. Events only request work; indexed deadlines, reconciliation checkpoints and generation state survive lost hints.

Receipt replay requires complete current-policy evidence; absence returns the ordinary ETL fallback. A receipt lookup is not permission to reuse a later delivery's observation identity. History/receipt retention is independent of active row expiry.

One process-local fair interruptible lock per configured artifact covers snapshot/build/install/ack. Canonical writes may advance required generation while building. Acknowledgement names the actually installed generation, and cached successful results retain advisory warnings. Lock order is artifact owner before short DB admission. Share one owner/raw-installer composition; separate processes must not share mutable output paths. This is not export's cross-process operation lease.

[Capability internals](../../../../../../../../../../docs/dev/canonical-record-lifecycle.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
