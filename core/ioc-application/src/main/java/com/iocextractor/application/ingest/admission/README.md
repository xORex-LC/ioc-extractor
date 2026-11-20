# Document admission handshake

## Purpose and boundary

Durable document occurrence state before content hashing and ordinary ingestion-ledger processing.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [DocumentAdmission](DocumentAdmission.java), [DocumentAdmissionPhase](DocumentAdmissionPhase.java), [DocumentAdmissionReservation](DocumentAdmissionReservation.java) | Versioned aggregate, stable pre-order reservation and forward checkpoints. |
| [DocumentAdmissionService](DocumentAdmissionService.java) | Reserve/register/claim/link/finalize and bounded recovery/retention. |
| [DocumentCandidateEvidence](DocumentCandidateEvidence.java), [DocumentExecutionState](DocumentExecutionState.java), [DocumentTerminalOutcome](DocumentTerminalOutcome.java) | Pre-hash filesystem evidence, bounded attempt/backoff metadata and terminal evidence. |

## Contracts and maintenance

Reserve the occurrence before dataframe rank allocation, then claim and link the source. Do not replace a pre-hash failure with a fresh occurrence merely to progress a queue. Retry/recovery keeps the persisted namespace and registration; contradictions fail closed.

The service coordinates short journal/registration operations without holding both storage locks together. Per-document attempt/backoff state is durable control data, not a job per IOC. Terminalization and exact registration cleanup can recover between local deletions. Adapter dispatch owns worker ordering/capacity and blocked-before-hash disposition.

[Capability internals](../../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
