# Managed import values and state

## Purpose and boundary

Immutable delivery/snapshot/stage evidence, safe result carriers and closed policy vocabulary. These are application values, not persistence rows or trusted imported identifiers.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ImportDelivery](ImportDelivery.java), [ImportDeliveryId](ImportDeliveryId.java), [ImportDeliveryState](ImportDeliveryState.java), [ImportDeliveryCheckpoint](ImportDeliveryCheckpoint.java), [ImportDeliveryTransition](ImportDeliveryTransition.java) | Versioned occurrence and forward checkpoints; terminal outcomes are not reopened. |
| [ImportCell](ImportCell.java), [ImportLogicalRow](ImportLogicalRow.java), [ImportArtifactBranch](ImportArtifactBranch.java), [ImportRowIssue](ImportRowIssue.java), [ImportRowWarning](ImportRowWarning.java) | Tri-state fields, row-atomic fan-out and safe bounded issues. |
| [ImportSnapshot](ImportSnapshot.java), [ImportSnapshotReference](ImportSnapshotReference.java), [ImportStage](ImportStage.java), [ImportContractPin](ImportContractPin.java), [ImportCommitEvidence](ImportCommitEvidence.java) | Pinned transport bytes, sealed stage/contract and dataframe commit proof. |
| [ImportManagedObjectId](ImportManagedObjectId.java), [ImportSourceOccurrenceKind](ImportSourceOccurrenceKind.java), [ImportSourceId](ImportSourceId.java), [ImportSourceReadiness](ImportSourceReadiness.java) | Transport-independent managed token, forward/replay distinction and source gate. |
| [DelimitedDialect](DelimitedDialect.java), [DelimitedInputLimits](DelimitedInputLimits.java), [ImportWorkspaceLimits](ImportWorkspaceLimits.java), [ImportWorkspaceCapacity](ImportWorkspaceCapacity.java) | Decoder-neutral dialect, parser/workspace limits and hysteretic capacity state. |

## Contracts and maintenance

A public imported ID is at most a requested reusable slot in a named profile/artifact namespace, never canonical identity. Canonical record/match keys are resolved after mapping. Missing rows do not imply deletion or lifecycle renewal.

Source/disposition tokens and snapshot references are opaque owned capabilities; do not parse paths or fabricate another source's reference. Global import sequence determines this lane's FIFO, while dataframe registration owns cross-delivery field precedence. Snapshot size/hash, stage seal and receipt evidence are different proofs.

Closed merge/routing/duplicate/failure/formula/slot policies must be admitted by the contract compiler. Safe status/issues omit raw IOC values; that does not automatically sanitize arbitrary exceptions emitted by a caller.

[Capability internals](../../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
