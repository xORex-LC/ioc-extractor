# com.iocextractor.application.ingest

Framework-free whole-file ingestion orchestration. The package coordinates owned
source units, durable ingestion state, extraction preparation, canonical
promotion/projection and terminal source disposition. Filesystem discovery,
Spring, JDBC and concrete CSV code remain in adapters/bootstrap.

## Contents

| Component | Responsibility |
|---|---|
| `IngestionService` | Synchronous ingest/recovery/reject and split prepare/promote operations |
| `IngestionRecord`, `IngestionStatus` | Durable occurrence status and monotonic terminal CAS |
| `SourceKey`, `SourceUnit`, `ArchivedSourceUnit` | Content identity and owned source references |
| `SourcePreparers` | Per-source artifact descriptors |
| `admission/DocumentAdmissionService` | Source reservation/order/claim/link/terminal handshake |
| `admission/DocumentExecutionState` | Durable document attempt/backoff metadata |

## Contracts

The daemon adapter reserves occurrence/order and claims the source before invoking
`PrepareIngestionUseCase`. A prepared handle owns sealed workspace references,
not IOC graphs. Preparation has no canonical effect. Promotion acquires shared
content-key exclusion, revalidates a reusable receipt, writes and projects through
the same durable saga as synchronous ingest. Cancel closes the prepared run and
workspace; it leaves claimed source ownership recoverable. Handles are single-use
and are handed between workers only after a publication barrier.

A duplicate is a new delivery observation. A valid receipt can confirm it without
ETL; changed/expired receipt falls back to extraction. Existing FAILED occurrences
remain terminal. CLAIMED recovery uses the same processing path. The run ledger
records write/projection checkpoints: precommit failure fails the run, whereas
postcommit failure recovers forward. Source archive and observation registration
terminalization are monotonic/idempotent.

Projection advisory diagnostics are delivered once per occurrence and added to
extraction completion. `CanonicalArtifactsChanged` is emitted after completed
run state; it carries artifact names, not rows/revisions. Publisher failure does
not change durable outcome. Consumers query truth and retain periodic reconcile.

Claim/ledger/dead-letter failures return typed INGEST carriers. Recovery preserves
STATE_TRANSITION_CONFLICT, and emits its newly created RECOVERY_FAILED once. The
adapter owns retry-attempt delivery; operational logging never changes processing
state. Diagnostics do not substitute for file/ledger transitions.

Document ordering is an adapter execution responsibility over the durable
admission model. Core does not select thread pools, dispatch priorities or parse
SQL. Shared content-key exclusion applies at promotion, so a newer preparation
cannot hold it while waiting for older work. Cross-process fencing is not implied.

See [ingestion](../../../../../../../../../docs/dev/ingestion.md) and
ADR-0038 in the repository documentation for startup, quotas and blocked pre-hash
failure disposition.
