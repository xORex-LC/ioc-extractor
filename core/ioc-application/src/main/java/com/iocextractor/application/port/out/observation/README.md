# Observation registration ports

## Purpose and boundary

Dataframe-owned delivery precedence and service-owned references for a recoverable two-store handshake.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [ObservationRegistrationStore](ObservationRegistrationStore.java) | Register a new occurrence, resume saved namespace/rank, terminalize and purge safely. |
| [ObservationAdmissionReferenceStore](ObservationAdmissionReferenceStore.java) | Recoverable import reference and CAS finalization/cleanup. |
| [ObservationRegistrationStatusReader](ObservationRegistrationStatusReader.java) | Safe aggregate rank/admission status without IOC or occurrence identifiers. |

## Contracts and maintenance

`registerNew` is for a genuinely new admitted occurrence; recovery calls `resume` with the persisted namespace. Source content keys and import sequence numbers never substitute for dataframe business order.

Safe purge can report REFERENCED, so keep recovery references until provenance/receipts release the registration. Independent store transactions are coordinated sequentially; no cross-database transaction or universal lock is implied. Read-only status must not allocate a rank.

[Capability internals](../../../../../../../../../../../docs/dev/ingestion.md) owns the cross-module flow. [Module map](../../../../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
