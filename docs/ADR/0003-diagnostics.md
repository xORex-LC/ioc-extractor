> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0003-diagnostics.md) (relative link addresses rebased on relocation).

<a id="0003--подсистема-диагностики-и-логирование"></a>
# 0003 — Diagnostics subsystem and logging

- **Status:** accepted (design); implementation pending
- **Date:** 2026-06-21
- **Related:** [observability](../dev/observability.md),
  [../principles.md](../PRINCIPLES.md), [0007-logging-observability.md](0007-logging-observability.md)

> **Amendment ([0007](0007-logging-observability.md)):** decision 8 (a custom
> versioned JSON log schema) is **revoked** — structured logging adopts **ECS**
> ([observability](../dev/observability.md)). Logging becomes a separate
> **observability** subsystem (a separate `platform-observability` module),
> distinct from diagnostics. Read the logging statements below with this amendment.

<a id="контекст"></a>
## Context

Diagnostics is separate business logic: services only construct and return
**data** (code + context), from which a separate subsystem builds a message/report/
log/pipeline metadata. We need patterns and references, a boundary between
“catalog in code” and “declarative UX templates”, and a logging approach.

<a id="решения"></a>
## Decisions

| # | Question | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Producer output | **Data**: `Diagnostic(code, context, severity, category)` — no text | Decouple producer from delivery/format; reuse in reports/logs/pipeline | Ready-made message string in the producer |
| 2 | Error source of truth | **Code catalog in source** (enumerable) | Adding an error means adding a constant; generated error map; stable IDs | Declarative list only (loses type safety/enumerability) |
| 3 | Message text | **Templates by code**: catalog default, measured declarative overrides (bundle/yaml) | UX/i18n near specific behavior, without excess | Hardcoded text; everything in configuration |
| 4 | Error flow | **Hybrid**: recoverable → `Result`/`Notification`; fatal → `DiagnosticException` at boundaries | Implements “return data” + collect-and-continue; explicit fatal failures | Exceptions only; data only |
| 5 | Message rendering | **Spring `MessageSource`** behind a `DiagnosticRenderer` port | Established template resolution + i18n | Custom formatter |
| 6 | Logging | **Rich DEBUG/TRACE + MDC** now; structured JSON later | Development tracing and readiness for log collectors | Sparse logs; a heavy JSON stack immediately |
| 7 | Diagnostic model | **Own `Diagnostic`** (informed by RFC 9457) | Lighter, without HTTP semantics; control over extension | Unmodified `zalando/problem`/`ProblemDetail` |
| 8 | Structured log | **Own versioned/extensible JSON schema** (semver) | Control and field evolution, collector readiness | Someone else's fixed schema |

<a id="паттерны"></a>
## Patterns

Catalog/Registry · Value Object + Builder · Notification (Fowler) · Result/Either ·
Strategy (`FailurePolicy`, `DiagnosticRenderer`) · Ports & Adapters · Observer/Event ·
Template/MessageFormat.

<a id="референсы"></a>
## References

- **RFC 9457 Problem Details** + **`zalando/problem`** — structured error data + type registry.
- **Apache Calcite `Resources`** — catalog in code + templates (`@BaseMessage`).
- **Spring `MessageSource` / `ProblemDetail`** — declarative templates, i18n.
- **M. Fowler — Notification** — accumulation.

<a id="следствия"></a>
## Consequences

- New ports: `DiagnosticSink`, `DiagnosticRenderer`, `MessageCatalog`; adapters
  `LoggingDiagnosticSink`, `MessageSourceRenderer`, `ReportDiagnosticSink`.
- The `Diagnostic` model + code catalogs form an agnostic core (a candidate for
  `platform/diagnostics`).
- Pipeline stages can attach diagnostics (metadata).
- Possible new dependencies during implementation: `logstash-logback-encoder`
  (later), optional `zalando/problem` (if we adopt its model instead of our own).

<a id="открытые-вопросы"></a>
## Open questions

None of substance. The precise set of `context` keys evolves as stages/codes are
added; the versioned log schema is established in
[observability](../dev/observability.md).
