# Document workspace and canonical artifact ports

## Purpose and boundary

Separate side-effect-free preparation, private cursor-backed scratch, canonical commit, projection and identity/recovery concerns.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ArtifactPreparer](ArtifactPreparer.java), [ArtifactIdentityResolver](ArtifactIdentityResolver.java), [ArtifactIdentityStore](ArtifactIdentityStore.java), [ArtifactIdBaseline](ArtifactIdBaseline.java) | Prepare/filter rows and validate artifact identity/id floors. |
| [DocumentPreparationWorkspaceFactory](DocumentPreparationWorkspaceFactory.java), [DocumentPreparationWorkspace](DocumentPreparationWorkspace.java), [DocumentSourceWorkspace](DocumentSourceWorkspace.java) | Quota-admitted invocation ownership, pinned source/scratch, append/seal/promotion state. |
| [DocumentProcessingPlan](DocumentProcessingPlan.java), [DocumentProcessingPlanFactory](DocumentProcessingPlanFactory.java), [DocumentProcessingSession](DocumentProcessingSession.java) | Execute one admitted plan with per-run preparers and invocation-local semantic state. |
| [RowSource](RowSource.java), [RowCursor](RowCursor.java), [CanonicalArtifactStreamReader](CanonicalArtifactStreamReader.java), [CanonicalArtifactRowConsumer](CanonicalArtifactRowConsumer.java), [CanonicalArtifactStreamResult](CanonicalArtifactStreamResult.java) | Repeatable prepared rows and reader-owned canonical streaming. |
| [CanonicalArtifactRepository](CanonicalArtifactRepository.java), [CanonicalMatchPlanner](CanonicalMatchPlanner.java), [RunLedger](RunLedger.java) | Canonical writes/active matching and write-to-project saga checkpoints. |
| [ArtifactProjection](ArtifactProjection.java), [ArtifactProjectionCommand](ArtifactProjectionCommand.java), [ArtifactProjectionResult](ArtifactProjectionResult.java), [lifecycle/README.md](lifecycle/README.md) | Derived installation/generation result and lifecycle transaction/work-store seams. |

## Contracts and maintenance

Prepared descriptors are not canonical truth. Source scratch must be disposed before seal or on close; sealed rows remain inside workspace lifetime. Open/close each cursor explicitly, including early exit. `RowSource.snapshot()` is intentional finite materialization, not a bounded production fallback; an owner may permit only one live cursor at a time.

Preparation itself does not reserve canonical IDs or write rows. Promotion pins private state before reservation and can retain recovery evidence after partial effects. Canonical commit and derived projection are separate calls. Expose the shared generation owner, not the raw installer, so concurrent callers cannot lose acknowledgement coverage.

Streaming callbacks execute synchronously under reader-owned resources; consumers must not retain rows or move callbacks to background tasks. Store implementations own concrete quotas, connections, active snapshot/time rules and atomicity. See interface Javadoc before implementing a port or treating a no-op as production durability.

[Capability internals](../../../../../../../../../../../docs/dev/processing.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
