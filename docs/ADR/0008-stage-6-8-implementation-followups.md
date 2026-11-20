> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0008-stage-6-8-implementation-followups.md) (relative link addresses rebased on relocation).

<a id="0008--ревью-реализации-этапов-68-и-технический-долг"></a>
# 0008 — Stages 6–8 implementation review and technical debt

<a id="статус"></a>
## Status

**Accepted.** Stages 6 (diagnostics), 7 (pipeline/Envelope) and 8 (observability)
are implemented and green (`mvn test`, 116 tests, golden unchanged). This note
records code-review outcomes: immediate fixes and deliberately deferred debt,
so they are not lost.

<a id="контекст"></a>
## Context

Stages 6–8 comply with the published architecture: `diagnostics` has no
SLF4J/Logback, the pipeline core is framework-free, observability connects through
`PipelineObserver`, and the three subsystems are separate (confirmed by ArchUnit).
Specific findings follow.

<a id="устранено-сразу-в-рамках-ревью"></a>
## Fixed immediately (during review)

| # | Finding | Fix |
|---|---|---|
| F1 | `DiagnosticSink` bean created but never consumed (dead wiring) | `IocExtractionService` drains `Envelope.diagnostics()` to `DiagnosticSink` at run end; bean supplied from `AppConfig`; default `NoopDiagnosticSink` |
| F2 | CLI hardcoded `ioc.mode = oneshot` | `ExtractCommand` obtains mode from `@Value("${ioc.observability.mode:oneshot}")` (without importing `bootstrap`, preserving the layer) |
| F3 | `DiagnosticException` message contained only `code.id()` | message = `id + context` |
| F4 | Speculative `DiagnosticCategory.VALIDATION` without codes | Removed (YAGNI) |
| F5 | `PipelineRunner` redundant catch looked like dead code | Comment added: outer catches apply only to `close()` |
| F6 | `platform-observability` depended on `application.pipeline` through `LoggingPipelineObserver` | Stage 9 moved reusable ETL contracts to `platform-etl` (`com.iocextractor.platform.etl`); observability no longer depends on `ioc-application` |

<a id="отложенный-долг-открыто"></a>
## Deferred debt (open)

| # | Debt | Reason for deferral | Destination |
|---|---|---|---|
| D1 | **Diagnostics is not integrated into the real flow.** No `Diagnostic` producer exists: adapters/stages throw `IocExtractorException`. Consequently `FailurePolicy` does no useful work and `DiagnosticSink` drains nothing. | Plans 6/7 explicitly deferred “mass replacement of exceptions with `Result`”; introduce producers as real errors appear. The seam is already live (F1). | Next substage: first producers (`SOURCE.READ_FAILED`, `SINK.WRITE_FAILED`, `PIPELINE.ITEM_SKIPPED`/dedup) |
| D3 | **ECS `event.duration` / `ioc.rows` types are strings.** They pass through MDC, which stores only `String`; `logback-ecs-encoder` serializes strings, whereas ECS types `event.duration` as `long`. Risk of an Elasticsearch mapping conflict. | Numeric per-event fields cannot be represented cleanly through MDC; a workaround was not forced. | When integrating an external collector / strict ECS mapping |
| D4 | `DiagnosticCatalogTest` scans `codes/` and asserts protocol `file`; it will fail if tests ever run from a JAR. | Not reproduced now (tests run from `target/classes`). | When packaging tests in a JAR |

<a id="следствия"></a>
## Consequences

- Reflected in current [observability.md](../dev/observability.md) (adoption-stage
  status, `TemplateDiagnosticRenderer` instead of MessageSource,
  `MessageCatalog`/i18n deferred, D1 banner and current `MdcScope.open().put(...)`
  API) and [processing.md](../dev/processing.md) (actual `Result` record,
  `Pipeline`/`PipelineRunner`/`PipelineObserver`, stage 7 marked realized).
- `event.dataset` is static (`ioc-extractor`), already synchronized in current
  `observability.md` (encoder filters reserved ECS keys out of MDC).

<a id="открытые-вопросы"></a>
## Open questions

- When to introduce D1: a small separate substage before modularization, or with
  the first real error source (ingestion, stage 10)?
