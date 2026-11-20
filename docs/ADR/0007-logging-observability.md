> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0007-logging-observability.md) (relative link addresses rebased on relocation).

<a id="0007--логированиеobservability-как-отдельная-подсистема-ecs"></a>
# 0007 — Logging/observability as a separate subsystem (ECS)

- **Status:** accepted (design)
- **Date:** 2026-06-21
- **Related:** [observability](../dev/observability.md), [processing](../dev/processing.md),
  [0003-diagnostics.md](0003-diagnostics.md)

> **Documentation maintenance 2026-07-21:** historical `logging.md` and
> `logging-taxonomy.md` were consolidated into the current capability map
> [observability.md](../dev/observability.md). Their names below refer to the
> original design documents at decision time.

<a id="контекст"></a>
## Context

The customer designed operational logging (`logging.md` + `logging-taxonomy.md`)
using ECS and asked whether logging and diagnostics should share one stage.
Design review recorded several refinements and revoked one earlier decision.

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale |
|---|---|---|---|
| 1 | Log event versus Diagnostic | **Separate subsystems.** A log event describes application execution; a Diagnostic is a data-processing outcome/problem | Different models, ports, owners; resolves earlier conflation |
| 2 | Structured log format | **ECS** (revokes [0003](0003-diagnostics.md) decision 8, a custom JSON schema) | Industry standard, tooling, no custom schema burden |
| 3 | Correlation | **`ioc.run.id` (run) / `ioc.source.id` (source)** as canonical application-correlation fields; ECS `trace.id`/`transaction.id` only with compatible tracing/APM context; durations use ECS `event.duration` | Do not replace ECS tracing semantics with an ordinary run ID; preserve the path to APM |
| 4 | MDC versus Envelope | **MDC derives from `Envelope.meta`** (source of truth); populate at a stage/adapter boundary, clear with `MdcScope.close()` | No duplicate state; no daemon-pool leakage |
| 5 | `event.dataset` | **Event field** (per event/stage), not a correlation key | Correct ECS semantics |
| 6 | Placement | **`platform-observability`** (MdcScope, LogEvent, ECS configuration) + separate bridge **`platform-diagnostics-logging`** (`LoggingDiagnosticSink`) | Preserve independence of base platform modules; bridge explicitly depends on diagnostics+observability |
| 7 | Stage 6 | **Split**: 6 diagnostics → 7 pipeline (`Envelope`) → 8 observability/logging | Logging reads `Envelope.meta`/diagnostics, so follows them; lower risk, easier review |

<a id="следствия"></a>
## Consequences

- Do not hardcode `ecs.version` (encoder supplies it); daemon defaults to `INFO`,
  per-item logging requires a flag/short window; `var/logs/` goes in `.gitignore`.
- Logging tests are isolated (in-memory appender / `OutputCapture`, no files).
- Documents aligned: schema section removed from diagnostics.md and
  cross-cutting.md; logging.md owns logging; roadmap renumbered.
- Dependency to add in stage 8: `co.elastic.logging:logback-ecs-encoder`
  (compare built-in structured logging after Boot 3.4+).

<a id="открытые-вопросы"></a>
## Open questions

- Exact `var/logs/*` set and rolling parameters, during daemon logging implementation.
- Diagnostic `Severity` → `log.level` mapping (table in logging-taxonomy.md),
  finalize with the first catalog codes.
