# Managed import driven boundaries

## Purpose and boundary

Separate source ownership/snapshots, strict parsing, private staging, durable ordering, canonical promotion, protected reports and retention.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ManagedImportSourceLifecycle](ManagedImportSourceLifecycle.java), [ImportSourceCapability](ImportSourceCapability.java), [ImportTerminalSourceRetention](ImportTerminalSourceRetention.java) | Transport-specific claim/materialization/disposition, positive readiness and exact terminal-object purge. |
| [ImportSnapshotStore](ImportSnapshotStore.java), [ImportSnapshotWriter](ImportSnapshotWriter.java), [ImportReplaySnapshotStore](ImportReplaySnapshotStore.java) | Immutable local bytes and protected replay input. |
| [DelimitedRecordReader](DelimitedRecordReader.java), [DelimitedRecordConsumer](DelimitedRecordConsumer.java), [DelimitedReadCommand](DelimitedReadCommand.java), [DelimitedHeaderReadCommand](DelimitedHeaderReadCommand.java) | Strict header/stream parsing without a concrete CSV library. |
| [ImportWorkspace](ImportWorkspace.java), [ImportWorkspaceWriter](ImportWorkspaceWriter.java), [CreateImportWorkspaceCommand](CreateImportWorkspaceCommand.java) | One-delivery private staging/quotas/seal/verification. |
| [ImportValueTransformRegistry](ImportValueTransformRegistry.java), [ImportValueValidatorRegistry](ImportValueValidatorRegistry.java), [ProcessedImportRowPreparer](ProcessedImportRowPreparer.java) | Admitted transforms/validators and authority-preserving processed strategy. |
| [ImportDeliveryLedger](ImportDeliveryLedger.java), [CanonicalImportWriter](CanonicalImportWriter.java), [CanonicalImportCommand](CanonicalImportCommand.java), [CanonicalImportResult](CanonicalImportResult.java), [ImportCommitEvidenceStore](ImportCommitEvidenceStore.java) | Global FIFO/CAS versus one cross-artifact dataframe transaction and receipt. |
| [ImportReportStore](ImportReportStore.java), [ImportTerminalRetentionStore](ImportTerminalRetentionStore.java), [ImportStatusReader](ImportStatusReader.java), [DataframeImportObserver](DataframeImportObserver.java), [ImportChangeSignalSource](ImportChangeSignalSource.java) | Protected terminal units, bounded read models, checkpoint observation and latency hints. |

## Contracts and maintenance

Service ledger owns order/retries, dataframe receipt owns canonical idempotency. One integrity-pinned canonical promotion commits all affected artifact/lifecycle/alias/slot/revision facts with its receipt. A response failure after commit must be recovered from that proof, not applied twice.

Claim succeeds only with exclusive transport ownership and durable private immutable bytes. Snapshot tokens and managed-object IDs are opaque, path-free authority references. A positive source probe does not certify producer ACL denial or every server semantic. Source-detached replay must not disposition/purge a forward producer object again.

Parsing/processing callbacks stream accepted or rejected logical rows with bounded issues. Row atomicity/fan-out and source-authority checks apply regardless of cache/preparer strategy. Close preparation sessions before sealing; verify private stage before promotion. Imported public IDs request export slots only, never canonical identity.

Terminal retention removes owned remote remnant before local protected report/source evidence and leaves the ledger until external cleanup succeeds. Operational events/observers never become commit authority. Interface Javadoc and downstream adapter TCK/contract tests own exact ownership/error/limit guarantees.

[Capability internals](../../../../../../../../../../../docs/dev/dataframe-import.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
