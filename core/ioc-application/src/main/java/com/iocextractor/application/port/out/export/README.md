# Immutable snapshot and slice ports

## Purpose and boundary

Separate reader-owned consistent snapshot, streaming formatting/publication, manifest serialization, durable formation state and delivery-aware retention.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [SnapshotSliceReader](SnapshotSliceReader.java), [SnapshotRowConsumer](SnapshotRowConsumer.java) | Resolve export mapping, then synchronous begin/artifact/row/end callbacks under one reader-owned snapshot. |
| [ArtifactSliceWriter](ArtifactSliceWriter.java), [SliceManifestCodec](SliceManifestCodec.java) | Deterministic staging/inspection/publication and versioned wire serialization. |
| [ArtifactRevisionReader](ArtifactRevisionReader.java), [ExportProgressStore](ExportProgressStore.java), [ExportRunLedger](ExportRunLedger.java), [ExportRunReader](ExportRunReader.java) | Cheap ordered revisions, durable progress, atomic terminal checkpoint and operational reads. |
| [ExportOperationGuard](ExportOperationGuard.java), [ExportObserver](ExportObserver.java) | Shared formation/recovery lease and durable-checkpoint observation. |
| [SliceRetentionStore](SliceRetentionStore.java), [SliceRetentionGuard](SliceRetentionGuard.java) | Verified whole-slice deletion with immediate delivery veto. |

## Contracts and maintenance

Resolve reusable export slots before callbacks; metadata/rows belong to the same snapshot. External slot identity is scoped to profile/artifact/slice, distinct from canonical IDs. The reader owns transaction/cursor cleanup for success, early exit and failure. The callback must not retain rows, call asynchronously or outlive the reader's scope.

The writer owns bytes/filesystem atomic visibility; it never advances saga ledger state itself. Recovery inspects exact manifest/marker/data evidence. The profile operation lease prevents crash recovery from consuming another live process's staging, complementing durable single-flight.

Progress completion and ledger terminal transition are one store operation; stale CAS does not grant another writer authority. The observer is operational and may run after durable effects. Retention operates on complete slices, never arbitrary leaf files; an immediate guard veto can leave count limits best-effort.

[Capability internals](../../../../../../../../../../../docs/dev/artifact-export.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
