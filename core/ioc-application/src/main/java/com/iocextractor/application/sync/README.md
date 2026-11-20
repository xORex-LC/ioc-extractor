# Remote fetch and publish coordination

## Purpose and boundary

Transport-neutral values and application services for read-only remote fetch and completed-slice delivery. Concrete sessions/protocol mechanics remain behind ports; these services also perform existing JDK NIO local landing/marker IO.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [RemoteSourceMonitor](RemoteSourceMonitor.java), [RemoteFetchService](RemoteFetchService.java), [RemoteFetchSources](RemoteFetchSources.java) | Complete detection/filtering versus bounded execution of an already detected batch or explicit fetch cycle. |
| [RemoteObject](RemoteObject.java), [RemoteObjectIdentity](RemoteObjectIdentity.java), [RemoteFetchRecord](RemoteFetchRecord.java), [RemoteFetchInFlightRegistry](RemoteFetchInFlightRegistry.java) | Current metadata identity, durable idempotency and process-local queue suppression. |
| [ArtifactPublishService](ArtifactPublishService.java), [PublishRecord](PublishRecord.java), [PublishStatus](PublishStatus.java), [PublishTarget](PublishTarget.java), [CompletedSlice](CompletedSlice.java) | Verified local-slice worklist, pair-ledger delivery and target selection. |
| [PublishLedgerSliceRetentionGuard](PublishLedgerSliceRetentionGuard.java), [PublishLedgerStatusCounts](PublishLedgerStatusCounts.java), [PublishLedgerHealthSummary](PublishLedgerHealthSummary.java) | Delivery pins and bounded status aggregates. |
| [RemoteErrorKind](RemoteErrorKind.java), [RemoteErrorDisposition](RemoteErrorDisposition.java), [RemoteTransportException](RemoteTransportException.java), [Retrier](Retrier.java), [RetryPolicy](RetryPolicy.java), [RetrySleeper](RetrySleeper.java) | Typed failure axis, code-owned micro/macro retry decision and injectable waits. |
| [RemoteChangeBatchDetected](RemoteChangeBatchDetected.java), [RemoteWatchTarget](RemoteWatchTarget.java), [PublishAtomicallyRequest](PublishAtomicallyRequest.java), [PublishReceipt](PublishReceipt.java), [SyncDiagnosticReporter](SyncDiagnosticReporter.java) | Control hints, thick publish intent/receipt and final failure diagnostics. |

## Contracts and maintenance

Monitor lists and filters; detected-batch fetch does not rediscover a directory. Ordinary fetch never claims/deletes producer input. The service downloads to private local staging, forces bytes and atomically lands a candidate before marking FETCHED. It is framework-free but not filesystem-free. Current `(path,size,mtime)` identity omits endpoint/source namespace ([OPS-11](../../../../../../../../../docs/KNOWN-ISSUES.md#ops-11)), and an existence check plus atomic move does not prevent concurrent input overwrite ([OPS-12](../../../../../../../../../docs/KNOWN-ISSUES.md#ops-12)). Polling cannot repair wrong identity. Coordinate distinct inbox names/writers until those issues are fixed.

Publish reconciliation creates missing slice/target ledger pairs from verified completed local slices. Event hand-off and periodic reconciliation use the same endpoint-keyed execution in bootstrap. Remote commit-marker evidence determines idempotent delivery; conflicting markers are not permission to replace a committed slice. The service's temporary marker download/read is JDK NIO, while remote IO belongs to `FileTransport`.

Only `RETRY_NOW` failures receive micro-retries; unreachable/resource pressure waits for the later reconcile policy, while authentication/permission/not-found/security-policy failures are terminal for that attempt. Interrupted retry waits preserve interruption and abort. In-flight sets and endpoint lanes are local accelerators, not cross-process correctness authority. Watch callbacks carry doorbells only; durable ledgers and periodic full detection/reconcile remain required.

Publish pins protect undelivered slices but do not turn a local formation saga into remote transactionality. Source/target credentials, SMB policy and live server compatibility belong to outer configuration/adapters and qualification.

[Capability internals](../../../../../../../../../docs/dev/sync.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
