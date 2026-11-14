# Whole-source ownership and journal ports

## Purpose and boundary

Required storage/source operations for document ingestion and pre-hash admission.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [SourceLifecycle](SourceLifecycle.java) | Private claim/hash/seal/archive/dead-letter and recoverable owned-source evidence. |
| [DocumentAdmissionJournal](DocumentAdmissionJournal.java), [IngestionLedger](IngestionLedger.java) | Versioned pre-hash journal versus ordinary occurrence status/terminal CAS. |
| [SourcePreparerFactory](SourcePreparerFactory.java) | Per-source preparer binding without concrete CSV/ID/storage integration. |

## Contracts and maintenance

Discovery/stability belongs to the driver; durable reservation/order precedes hash-independent claim. The journal and ingestion ledger serve different recovery windows and must retain the original observation/namespace rather than fabricating a retry identity.

Use typed transition outcomes for expected-state conflicts and idempotent terminal rejection. Source/archive operations may have completed before their response was lost; recovery must inspect owned evidence and advance forward. Adapter layout/fsync/atomic-move assumptions require their own qualification.

[Capability internals](../../../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
