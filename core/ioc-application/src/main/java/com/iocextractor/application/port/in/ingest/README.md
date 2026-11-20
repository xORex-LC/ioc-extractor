# Whole-source ingestion entry ports

## Purpose and boundary

Drive already detected/claimed document occurrences and recovery/rejection without leaking the ledger to the adapter.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [IngestSourceUseCase](IngestSourceUseCase.java), [IngestSourceCommand](IngestSourceCommand.java), [IngestSourceResult](IngestSourceResult.java) | Synchronous processing with durable observation and source content identity. |
| [PrepareIngestionUseCase](PrepareIngestionUseCase.java), [PreparedIngestion](PreparedIngestion.java) | Owned preparation followed by single-use serial promotion. |
| [RecoverIngestionUseCase](RecoverIngestionUseCase.java), [RejectIngestionUseCase](RejectIngestionUseCase.java), [IngestionRejectionResult](IngestionRejectionResult.java) | Forward compensation and idempotent terminal rejection. |

## Contracts and maintenance

The adapter owns discovery/stability/private claim and durable admission before preparation. Prepared handles retain source/workspace ownership and must be safely published/closed; worker completion order is not canonical precedence. Promotion acquires content-key exclusion and revalidates reusable receipt evidence.

Terminal rejection distinguishes the first successful transition from an already-rejected occurrence. Recovery must not reopen FAILED work or allocate a new observation/rank. A retry is not automatically a new delivery. Typed result/diagnostics do not substitute for durable source/ledger transitions.

[Capability internals](../../../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
