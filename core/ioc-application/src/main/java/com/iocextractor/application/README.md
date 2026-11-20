# Application capability map

## Purpose and boundary

Application services, models and ports shared by the driving and driven layers. Dependencies point inward; direct use of application value types by adapters is expected, not a claim that only interfaces can cross a module boundary.

## Structure

| Entry points / files | Responsibility |
|---|---|
| [port/README.md](port/README.md) | Driving versus driven seams and collaborator ownership. |
| [service/README.md](service/README.md), [pipeline/README.md](pipeline/README.md) | Extraction invocation ownership, stages, payloads and outcome mapping. |
| [artifact/README.md](artifact/README.md), [observation/README.md](observation/README.md) | Identity/mutation/lifecycle and cross-delivery ordering. |
| [ingest/README.md](ingest/README.md), [dataframeimport/README.md](dataframeimport/README.md) | Document occurrences and managed structured deliveries have distinct recovery protocols. |
| [export/README.md](export/README.md), [sync/README.md](sync/README.md), [cadence/README.md](cadence/README.md) | Immutable formation, remote delivery/fetch and pure cadence policies. |
| [maintenance/README.md](maintenance/README.md), [observability/README.md](observability/README.md), [classification/README.md](classification/README.md) | Retention units, computed trace values and historical package marker. |

## Contracts and maintenance

Use a capability README for its local responsibilities, then its port/Javadoc for exact guarantees. Generic `Envelope`, `Stage`, `Pipeline` and `PipelineRunner` live in `platform-etl`; this module owns IOC payloads and stage implementations. Shared IOC classification is implemented in `ioc-processing`, not duplicated in the marker package. Configuration binding, scheduler/executor construction and actuator/SLF4J projection remain bootstrap responsibilities.
[Module map](../../../../../../README.md) owns dependencies and verification; exact member guarantees remain in Javadoc.
