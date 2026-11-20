# Lifecycle driving ports

## Purpose and boundary

Narrow operations for canonical admission, activation, reconciliation, projection convergence, receipt replay and independent retention.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [PrepareLifecycleAdmissionUseCase](PrepareLifecycleAdmissionUseCase.java), [ResumeLifecycleActivationUseCase](ResumeLifecycleActivationUseCase.java) | Common barrier and resumable explicit activation. |
| [ReconcileExpiredRecordsUseCase](ReconcileExpiredRecordsUseCase.java), [ConvergeArtifactProjectionsUseCase](ConvergeArtifactProjectionsUseCase.java) | Bounded expiry and durable generation convergence. |
| [ReplayConfirmationReceiptUseCase](ReplayConfirmationReceiptUseCase.java), [RunLifecycleHistoryRetentionUseCase](RunLifecycleHistoryRetentionUseCase.java) | Proof-based replay/fallback and history/receipt cleanup. |

## Contracts and maintenance

Call admission before stateful canonical work; runtime schedulers must not bypass incomplete activation/recovery. Expiry, projection and history retention remain separate operations with separate durable evidence. Receipt replay can return empty when complete current-policy proof is unavailable; callers must retain the ordinary processing fallback.

These ports do not schedule timers or report actuator health. Explicit effective-time/half-open activity rules and canonical transaction guarantees belong to the lifecycle model and driven ports.

[Capability internals](../../../../../../../../../../../../docs/dev/canonical-record-lifecycle.md) owns the cross-module flow. [Module map](../../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
