# Remote sync driving ports

## Purpose and boundary

Commands/results for explicit remote fetch/publish and event-driven execution of known work. Feature/filter selection can be validated before activating lazy transport/storage.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ValidateSyncSelectionUseCase](ValidateSyncSelectionUseCase.java), [RemoteFetchUseCase](RemoteFetchUseCase.java), [RemoteFetchCommand](RemoteFetchCommand.java), [RemoteFetchResult](RemoteFetchResult.java), [FetchRemoteObjectsCommand](FetchRemoteObjectsCommand.java) | IO-free preflight, explicit detection cycle and fetch of already detected identities. |
| [ArtifactPublishUseCase](ArtifactPublishUseCase.java), [ArtifactPublishCommand](ArtifactPublishCommand.java), [ArtifactPublishResult](ArtifactPublishResult.java), [ArtifactPublishExecutionResult](ArtifactPublishExecutionResult.java), [PublishCompletedSliceCommand](PublishCompletedSliceCommand.java) | Reconcile durable work and execute selected/known-slice delivery. |

## Contracts and maintenance

Fetch is read-only against producer objects; managed import ownership is a separate capability. Detected-object execution is distinct from full listing. A dry-run may inspect/validate work without performing its durable mutation, as specified by each method; it is not a promise of zero reads.

Local export formation, publish ledger state and work attempted by this invocation are different outcomes. Postcommit hints can be lost; the periodic detection/reconcile path stays active. Global/source/endpoint filters must be admitted before stateful work and do not change identity or fencing guarantees.

[Capability internals](../../../../../../../../../../../docs/dev/sync.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
