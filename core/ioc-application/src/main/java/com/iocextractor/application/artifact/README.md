# Canonical artifact and preparation model

## Purpose and boundary

Storage-neutral rows, identities, deferred write plans and document run recovery. Canonical truth and mutable projections are different authorities.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ArtifactRow](ArtifactRow.java), [ArtifactRowKey](ArtifactRowKey.java), [CanonicalArtifact](CanonicalArtifact.java) | Ordered defensive row data, identity values and compatibility snapshots. |
| [CanonicalArtifactIdentityResolver](CanonicalArtifactIdentityResolver.java), [CanonicalArtifactKeyResolver](CanonicalArtifactKeyResolver.java), [CanonicalKeyDefinition](CanonicalKeyDefinition.java), [CanonicalKeyMaterial](CanonicalKeyMaterial.java), [CanonicalMatchPlan](CanonicalMatchPlan.java) | Artifact-specific/versioned record and match formulas plus collision-check material. |
| [ArtifactWritePlan](ArtifactWritePlan.java), [PreparedArtifactRow](PreparedArtifactRow.java), [RoutedArtifactCandidate](RoutedArtifactCandidate.java), [RowSources](RowSources.java) | Deferred IDs, cursor-backed prepared rows and finite-list adapters. |
| [ArtifactIdSequence](ArtifactIdSequence.java), [ArtifactIdReservation](ArtifactIdReservation.java), [ArtifactIdStrategy](ArtifactIdStrategy.java) | Independent monotonic reservation; failed ranges are not returned. |
| [CanonicalWriteCommand](CanonicalWriteCommand.java), [CanonicalWriteResult](CanonicalWriteResult.java), [CanonicalRecordMutationOutcome](CanonicalRecordMutationOutcome.java) | Actual insert/public-update/metadata outcomes with optional ordered precedence. |
| [IngestRun](IngestRun.java), [IngestRunStatus](IngestRunStatus.java), [IngestRunRecoveryService](IngestRunRecoveryService.java) | Write-to-project saga evidence and forward projection recovery. |
| [DocumentObservationSelection](DocumentObservationSelection.java), [FingerprintFraming](FingerprintFraming.java), [StableArtifactId](StableArtifactId.java) | Plan accounting policy, durable fingerprint framing and retained stable-ID value. |
| [policy/README.md](policy/README.md), [lifecycle/README.md](lifecycle/README.md) | Pure candidate/field policies and validity/projection convergence. |

## Contracts and maintenance

`ArtifactRow` retains column order and NULL values in a defensive map snapshot. Final-row identity is artifact-specific; digest plus canonical material prevents treating a hash collision as equality. Record keys and match aliases have different purposes; ambiguous active matches fail closed.

`ArtifactWritePlan.rows` is repeatable but borrowed from its owner. Materializing a commit command reserves IDs once for that attempt and keeps deterministic row positions. Its legacy `materialize()` convenience explicitly builds a finite snapshot; it is not the bounded production document path. Canonical IDs are not immutable-export slots and need not be gapless.

A write result distinguishes newly inserted rows, public updates and metadata-only changes. Recovery reprojects already committed canonical truth instead of rerunning an accepted write as though nothing happened. No-op implementations are explicit composition choices, not automatic substitutes for durable operation. `StableArtifactId` remains present while its separate retirement decision is pending.

[Capability internals](../../../../../../../../../docs/dev/storage.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
