# Remote transport and sync ledgers

## Purpose and boundary

Hide protocol sessions/handles and separate remote file operations from local completed-slice discovery, durable work and optional push notifications.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [FileTransport](FileTransport.java) | Stateless application-facing list/stat/get/delete/thick atomic-publish operations; adapter owns sessions. |
| [RemoteFetchLedger](RemoteFetchLedger.java), [PublishLedger](PublishLedger.java), [CompletedSliceCatalog](CompletedSliceCatalog.java) | Fetch identity, per-slice/target delivery saga and verified local worklist. |
| [RemoteChangeSignalSource](RemoteChangeSignalSource.java), [RemoteChangeSignalHandler](RemoteChangeSignalHandler.java), [RemoteChangeWatch](RemoteChangeWatch.java) | Optional doorbell source/callback/close handle, separate from file operations. |

## Contracts and maintenance

Stateless port calls do not imply no I/O or no adapter caching. The thick publish request lets an adapter verify its own marker-last/rename protocol without leaking a transport session or creating a fine-grained storage API. General delete is for explicit owned cleanup; ordinary fetch does not mutate producer input.

Push is optional and carries no file/IOC facts. Reconnect/overflow signals request ordinary detection; lost hints are recovered by complete polling/reconcile. Watches own their adapter resources and have explicit best-effort close semantics, not a promise of durable notification delivery.

Ledger persistence and process-local in-flight tracking currently use fetch metadata identity without endpoint/source namespace; [OPS-11](../../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-11) remains open. A supported port shape does not qualify SMB ACL/encryption/session capacity or fix local landing races.

[Capability internals](../../../../../../../../../../../docs/dev/sync.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
