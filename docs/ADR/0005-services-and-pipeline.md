> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0005-services-and-pipeline.md) (relative link addresses rebased on relocation).

<a id="0005--сервисы-конвейер-и-защита-границ"></a>
# 0005 — Services, pipeline and boundary enforcement

- **Status:** accepted (design)
- **Date:** 2026-06-21
- **Related:** [architecture](../ARCHITECTURE.md), [processing](../dev/processing.md),
  [../boundaries.md](../BOUNDARIES.md), [../modularization.md](../MODULARIZATION.md)

<a id="контекст"></a>
## Context

Establish the DDD direction (pure logic → isolated services), select the ETL
pipeline approach and detail boundary enforcement and module decomposition.

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Logic organization | **Isolated services behind ports** (DDD), catalog + map | Maintainability, scalability, parallel development; extract a module without rewriting | Monolithic services; direct implementation coupling |
| 2 | Pipeline approach | **Pipes-and-Filters (EIP) + Envelope + Result/Either + functional composition** | Extensible stages (OCP), diagnostics as metadata, collect-and-continue, unified batch/stream | Imperative orchestrator only; heavy pipeline framework in the core |
| 3 | Pipeline framework | **Own thin core** (`Stage`/`Envelope`/`Result`); EIP framework (Spring Integration) only at the ingestion edge | Clean hexagon, KISS; SI where it is strong (file intake) | SI/Camel inside the core |
| 4 | Result/Either | **Own thin `Result`**; Vavr optional | No unnecessary core dependency | Mandatory Vavr |
| 5 | Boundary enforcement | **ArchUnit now** (single module) + enforcer with parent POM | Cheap, makes the build fail before module decomposition | Review only; wait for modules |
| 6 | Module granularity | **Coarse, incremental**; services already module-ready behind ports | Modularity benefits without premature reactor complexity | A module for every service immediately |

<a id="следствия"></a>
## Consequences

- At decision time, a separate service catalog (catalog + map) and
  [processing.md](../dev/processing.md) (pipeline approach) became authoritative
  documents. **Update 2026-07-19:** the catalog was removed as a costly duplicate
  projection; the current responsibility map lives in
  [ARCHITECTURE.md](../ARCHITECTURE.md), [MODULARIZATION.md](../MODULARIZATION.md)
  and module READMEs.
- `IocExtractionService` evolves into an explicit `Pipeline` of `Stage`s with
  `Envelope`/`Result`, without changing domain services.
- [boundaries.md](../BOUNDARIES.md) gains specific ArchUnit/enforcer rules;
  [modularization.md](../MODULARIZATION.md) gains a “service → module” map and granularity.
- Module candidates: `platform-{regex,diagnostics,errors}`, `ioc-domain`,
  `ioc-application`, `adapter-{source-tika,sink-csv,lookup-csv,psl,ingest,cli}`,
  `ioc-app`.

<a id="открытые-вопросы"></a>
## Open questions

- The precise `Envelope.meta` schema (attribute set) will be refined during stage implementation.
- When to introduce the parent POM/reactor (modularity stage 1), according to
  readiness for parallel development.
