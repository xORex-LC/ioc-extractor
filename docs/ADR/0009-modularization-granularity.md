> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0009-modularization-granularity.md) (relative link addresses rebased on relocation).

<a id="0009--гранулярность-модулей-и-критерий-разреза-домена"></a>
# 0009 — Module granularity and criteria for splitting the domain

<a id="статус"></a>
## Status

**Accepted.** Defines stage 9 reactor granularity and the criterion for extracting
a domain capability into a separate artifact.

<a id="контекст"></a>
## Context

The project principle is “each agnostic service within its own boundaries,
parallel development, clarity”. The first draft yielded approximately 19 modules,
including six separate domain capabilities (`refang/extraction/features/classification/
attribution/kernel`). The question: is that too fine-grained, or conversely are
boundaries failing because a new feature touches several stages?

<a id="решения"></a>
## Decisions

<a id="1-coupling--cross-cutting-feature"></a>
### 1. Coupling ≠ cross-cutting feature

“A change touches several stages” does **not** indicate coupling. These are two
different concepts:

- **Coupling** means changing A necessarily breaks B (A reaches into B's internals).
  Test: “can I change `feature` internals without touching `classify`?” Our stages
  communicate only through stable contracts (payload records / domain types),
  with ordering in one `Pipeline`. Thus coupling is **low** and boundaries intact.
- **Cross-cutting feature** means one business feature has an aspect in several
  single-responsibility components. With **horizontal** decomposition (pipeline
  steps), every feature is vertical and crosses several steps. This is a property
  of the decomposition axis, not a boundary defect.

<a id="2-ось-новый-тип-ioc-вынесена-в-конфиг-а-не-в-код"></a>
### 2. The “new IOC type” axis lives in configuration, not code

The architecture is declarative (patterns, `classify.rules`, `refang.rules`,
`columns` in `application.yml`). Therefore:

- A type in an existing structural family (hash/url/ip/domain-like) requires
  **configuration only** + an `IndicatorType` value (kernel); stage code stays unchanged.
- A structurally new type (email `@`, IPv6) touches `kernel` (enum) and the
  structure-handling component (`feature`), but each changes independently behind
  its own contract.

Result: a typical “add a type” change does not ripple even through code, much
less through modules.

<a id="3-гранулярность-реактора--средняя-14-модулей--parent"></a>
### 3. Reactor granularity is medium (14 modules + parent)

Previously 19 (domain split into seven); now 14 by collapsing the domain into
one artifact: platform (5) + core (`ioc-domain` + `ioc-application` = 2) + adapters
(6) + `ioc-app` (1).

| Group | Decomposition | Rationale |
|---|---|---|
| `platform-*` (errors, etl, diagnostics, observability, diagnostics-logging) | **Separate artifacts** | Truly reusable libraries with independent lifecycles |
| `ioc-domain` | **One artifact**; capabilities = packages + ArchUnit DAG | One bounded context, one ubiquitous language; high cohesion does not mean “too large” |
| `ioc-application` | Separate | Use cases + ports + IOC stages |
| `adapter-*` (6) | **Separate artifacts** | Each isolates its external dependency (Tika/CSV/Guava/RE2J/picocli), a CRP reference |
| `ioc-app` | Separate | Composition root, executable JAR |

Splitting the domain into six artifacts **does not reduce coupling** (already
low through contracts+ArchUnit); it only adds release ceremony (six versions/PRs)
to an inherently cross-cutting change. An artifact pays off through **independent
compilation/release/reuse**, not visual clarity alone.

<a id="4-критерий-выноса-доменной-capability-в-свой-артефакт"></a>
### 4. Criterion for extracting a domain capability into its own artifact

Extract **only under a real driving force**: a separate team, release cadence or
reuse in another product (“extract later”). Do not extract for aesthetics/clarity.

`refang` is the **first and currently only candidate**: an island of coupling
(pure `text→text`, zero internal domain dependencies) and a genuinely reusable
refanging library. Other capabilities (`extract/feature/classify/attribute`)
share `kernel`, while `classify` is closely tied to `IndicatorFeatures`; keep them together.

<a id="5-защита-границ--слоями"></a>
### 5. Boundary enforcement in layers

- **Maven reactor dependencies:** basic compile-time barrier.
- **Maven Enforcer** (`bannedDependencies`, toolchain/plugin guards): no
  Spring/Tika/CSV/Guava/RE2J in core/platform.
- **ArchUnit:** internal domain capability DAG (`refang` depends on nothing;
  `classify` → `feature`+`model`; `extract`/`attribute` → `model`; no cycles).
- **Deferred debt:** `dependencyConvergence`, Spring Modulith/canvas and JPMS.
  Maven reactor + Enforcer + ArchUnit suffice for stage 9.

<a id="следствия"></a>
## Consequences

- Plan 0009 changes: one `ioc-domain` instead of six domain modules; internal
  ArchUnit DAG added; extraction criterion and `refang` as candidate 1 established.
  Spring Modulith/canvas and `dependencyConvergence` are outside stage 9
  implementation and remain separate debt.
- Horizontal decomposition (by step) is deliberate; vertical decomposition (by
  IOC type) is unnecessary because the type axis lives in configuration.

<a id="открытые-вопросы"></a>
## Open questions

- `StageName` resolved: generic `StageId` lives in `platform-etl`; IOC-specific
  stage implementations/payloads remain in `ioc-application`.
- Regex SPI: `PatternEngine` in `ioc-domain` (inside extract) versus a separate
  `platform-regex-api` (default: port in extract, implementation in adapter).
- Spring Modulith/canvas remains separate debt after reactor structure stabilizes.
