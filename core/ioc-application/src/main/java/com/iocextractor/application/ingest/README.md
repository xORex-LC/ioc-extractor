# Whole-file document ingestion

## Purpose and boundary

Coordinate owned source units, durable delivery status, preparation, canonical promotion/projection and terminal disposition. File discovery, stabilization, thread pools and concrete ledgers remain outward.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [IngestionService](IngestionService.java), [IngestionRecord](IngestionRecord.java), [IngestionStatus](IngestionStatus.java), [IngestionLedgerTransition](IngestionLedgerTransition.java) | Synchronous ingest/recovery/rejection plus split prepare/promote. |
| [SourceKey](SourceKey.java), [SourceUnit](SourceUnit.java), [ClaimedSource](ClaimedSource.java), [ArchivedSourceUnit](ArchivedSourceUnit.java), [SourcePreparers](SourcePreparers.java) | Content identity, occurrence-owned paths and per-source preparation. |
| [IngestionLifecycleSupport](IngestionLifecycleSupport.java) | Optional confirmation-receipt/lifecycle collaborators. |
| [admission/README.md](admission/README.md) | Durable order/reservation/claim/link/terminal handshake. |

## Contracts and maintenance

The adapter reserves occurrence/order and privately claims a source before preparation. A prepared handle contains sealed workspace state, not a retained IOC graph. Promotion obtains shared content-key exclusion and revalidates reusable receipts; preparation must not hold that exclusion while waiting for older work. Handle publication/cancellation belongs to the driving dispatcher; promotion is single-use.

Repeated bytes represent a new observation. A complete valid receipt can confirm it without ETL; changed/expired proof falls back. FAILED occurrences stay terminal. Run-ledger precommit failure and postcommit forward recovery are distinct; source archive/registration finalization must be monotonic and idempotent. A cancelled preparation releases its local owner while leaving claimed ownership recoverable.

Projection advisory diagnostics join extraction completion once per occurrence. `CanonicalArtifactsChanged` follows completed durable run state and carries names, not IOC rows. Publisher failure cannot roll back accepted state; periodic reconcile remains required. Reject distinguishes newly persisted REJECTED from ALREADY_REJECTED without requiring the adapter to inspect the ledger directly. Process-local content exclusion is not cross-process fencing.

[Capability internals](../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
