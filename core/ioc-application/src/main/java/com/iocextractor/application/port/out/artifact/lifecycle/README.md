# Canonical lifecycle driven ports

## Purpose and boundary

Client-shaped transactions and durable read/work state for validity, expiry, activation, receipts and projection convergence.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [CanonicalArtifactWriter](CanonicalArtifactWriter.java), [CanonicalObservationStore](CanonicalObservationStore.java) | Confirm one artifact and acknowledge an observation terminally. |
| [ActiveArtifactReader](ActiveArtifactReader.java), [ExpiredArtifactStore](ExpiredArtifactStore.java), [LifecycleReconciliationStore](LifecycleReconciliationStore.java) | Explicit-asOf active reads, indexed deadlines and bounded atomic expiry/checkpoints. |
| [ConfirmationReceiptStore](ConfirmationReceiptStore.java), [LifecycleControlStore](LifecycleControlStore.java), [LifecycleActivationStore](LifecycleActivationStore.java), [LifecycleHistoryStore](LifecycleHistoryStore.java) | Proof lookup/retention, one-way activation and historical cleanup. |
| [ArtifactProjectionWorkStore](ArtifactProjectionWorkStore.java), [LifecycleClockInspector](LifecycleClockInspector.java), [LifecycleStatusReader](LifecycleStatusReader.java) | Generation acknowledgements, clock confidence and safe aggregate state. |

## Contracts and maintenance

A writer acquires storage ownership, samples effective time once and atomically commits one artifact's rows/provenance/lifecycle/receipt marker/revision/generation. Replaying the same observation/artifact returns the prior durable result without renewing or comparing original payload again. It is not a transaction over all document artifacts.

Active reads use `validUntil > asOf`. Expiry archives/deletes/requests projection atomically; it does not advance insert-driven revision. Projection acknowledgement names actual installed coverage and must not hide later required work. Receipt/history cleanup and read-only clock inspection do not mutate business rows as an incidental health action.

These are storage-neutral contracts: scheduling, actuator, SQL schema/version and connection ownership belong to runtime/adapters. Implementations should bind their shared TCK and real failure-window tests downstream.

[Capability internals](../../../../../../../../../../../../docs/dev/canonical-record-lifecycle.md) owns the cross-module flow. [Module map](../../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
