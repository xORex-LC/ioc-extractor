# Durable observation ordering

## Purpose and boundary

Values and short handshakes that assign one dataframe-owned precedence rank to a delivery occurrence across document and managed-import paths.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ObservationOrder](ObservationOrder.java), [OccurrencePosition](OccurrencePosition.java), [RegisteredObservation](RegisteredObservation.java), [ObservationOrigin](ObservationOrigin.java) | Positive delivery rank, zero-based mapped position and namespace-bound authority reference. |
| [ObservationOrderedExtractionDecorator](ObservationOrderedExtractionDecorator.java) | Register/finalize oneshot work, bypassing registration for dry-run. |
| [ManagedImportObservationAdmission](ManagedImportObservationAdmission.java), [ObservationAdmissionReference](ObservationAdmissionReference.java) | Link/resume import order and safely finalize/purge recovery references. |
| [ObservationOrderingPolicy](ObservationOrderingPolicy.java), [ObservationRegistrationStatus](ObservationRegistrationStatus.java), [ObservationRegistrationPurgeOutcome](ObservationRegistrationPurgeOutcome.java) | Activation, safe operational counts and exact cleanup outcomes. |

## Contracts and maintenance

A delivery observation ID, a repeatable source content key, import sequence number and dataframe rank are not interchangeable. Recovery must resume the saved observation/namespace and verify it is unchanged; allocating a new rank would change field precedence.

Service/file references and dataframe registration are advanced by short sequential operations, not a cross-database transaction. Terminal retention keeps the reference while provenance/receipt still refers to the rank; after safe PURGED/MISSING evidence the reference can be removed idempotently. Oneshot dry-run skips this decorator's durable registration, but extraction may still read/use scratch and outer admission has its own contract.

[Capability internals](../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
