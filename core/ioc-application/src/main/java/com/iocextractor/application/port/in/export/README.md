# Immutable export driving ports

## Purpose and boundary

Validate a profile without side effects, form a complete immutable slice, recover incomplete work and sweep whole-slice retention.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ValidateExportProfileUseCase](ValidateExportProfileUseCase.java), [ExportArtifactsUseCase](ExportArtifactsUseCase.java), [ExportArtifactsCommand](ExportArtifactsCommand.java), [ExportArtifactsResult](ExportArtifactsResult.java) | IO-free selection before resolving the stateful export graph. |
| [RecoverExportUseCase](RecoverExportUseCase.java), [RecoverExportProfileUseCase](RecoverExportProfileUseCase.java) | Standalone versus already-leased profile recovery. |
| [RunSliceRetentionUseCase](RunSliceRetentionUseCase.java), [SliceRetentionResult](SliceRetentionResult.java) | Delivery-aware retention by profile. |

## Contracts and maintenance

`ExportArtifactsResult` reflects formation, not remote delivery. An unchanged pre-gate can return SKIPPED without a created run; later same-byte output with newer coverage still forms a new delivery-significant slice.

Formation and recovery share the cross-process profile operation lease. `RecoverExportProfileUseCase` assumes the owner already holds it; standalone recovery must acquire it. Export uses one consistent reader-owned callback snapshot and retains manifest coverage for recovery, not a later canonical reread. Remote publish has its own ports/ledger.

[Capability internals](../../../../../../../../../../../docs/dev/artifact-export.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
