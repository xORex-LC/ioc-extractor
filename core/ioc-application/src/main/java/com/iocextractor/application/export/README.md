# Immutable artifact emission

## Purpose and boundary

Resolved plans, snapshot/manifest evidence, formation saga and forward recovery. This package does not encode CSV/JSON, access JDBC or manipulate filesystem paths.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ExportProfile](ExportProfile.java), [ExportMode](ExportMode.java), [ExportFormat](ExportFormat.java), [ExportArtifactSpec](ExportArtifactSpec.java), [ExportPlan](ExportPlan.java), [ArtifactSchemaFingerprint](ArtifactSchemaFingerprint.java) | Ordered public format/schema/identity/mapping and deterministic plan identity. |
| [SnapshotRequest](SnapshotRequest.java), [SnapshotMetadata](SnapshotMetadata.java), [SnapshotArtifactMetadata](SnapshotArtifactMetadata.java), [ArtifactCoverage](ArtifactCoverage.java) | Consistent snapshot and exact revision/changed-at/upper-ID coverage. |
| [SliceManifest](SliceManifest.java), [SliceArtifactManifest](SliceArtifactManifest.java), [StagedSlice](StagedSlice.java), [AvailableSlice](AvailableSlice.java), [SliceInspection](SliceInspection.java) | Versioned integrity root and verified staging/availability evidence. |
| [ExportRun](ExportRun.java), [ExportRunStatus](ExportRunStatus.java), [ExportProgress](ExportProgress.java), [ArtifactRevision](ArtifactRevision.java) | Formation checkpoints and durable change-detection progress. |
| [ExportService](ExportService.java), [ExportChangeDetector](ExportChangeDetector.java), [ExportRunRecoveryService](ExportRunRecoveryService.java) | Coordinate ports, decide redundancy and recover from durable evidence. |
| [SliceDescriptor](SliceDescriptor.java), [SliceRetentionService](SliceRetentionService.java), [StandaloneSliceRetentionGuard](StandaloneSliceRetentionGuard.java), [SliceCompleted](SliceCompleted.java) | Whole-slice retention, delivery veto and post-completion hint. |

## Contracts and maintenance

Formation is `STARTED -> STAGED -> AVAILABLE -> COMPLETED`, with typed terminal SKIPPED/FAILED outcomes. Validate the profile before acquiring the per-profile cross-process operation lease; hold it across recovery and formation alongside durable single-flight. Advance ledger checkpoints only after their durable side effects. A cheap unchanged revision/plan pre-gate can skip without creating a run.

After materialization, SKIPPED requires equal plan, per-artifact bytes and covered revisions. A newer revision produces a new completed delivery even if reusable slots make bytes identical. The default CRLF/all-quoted layout retains manifest v1; other supported layouts use v2. Schema identity, mapping/plan hash, file hashes and manifest-marker hash are different proofs. Slot-based upper ID is not canonical row ID.

Recovery uses ledger plus inspected staging/final evidence, never a fresh canonical snapshot. It can finish a crash after rename or progress update; corrupt/conflicting evidence fails rather than being overwritten. `SliceCompleted` follows durable completion in normal and recovery paths; publisher failure is non-authoritative. Operational observers may be called after effects, so adapters must not pretend callback failure can roll back a completed write.

Retention treats each integrity-valid completed directory as one unit and counts independently per profile. Recheck the delivery guard immediately before delete; a pinned slice can leave count limits best-effort. Formation state does not track remote delivery: publish ledger/retention guards already exist in `sync`. APPEND remains a declared future mode, not an implemented formation path.

[Capability internals](../../../../../../../../../docs/dev/artifact-export.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
