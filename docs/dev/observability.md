# Diagnostics и operational observability

Observability состоит из двух связанных, но разных моделей. `Diagnostic`
является результатом обработки данных и может влиять на completion/failure
policy. Operational `LogEvent` описывает исполнение, IO, lifecycle, retry и
координацию. Exception остаётся механизмом переноса сбоя между границами и не
заменяет ни одну из этих моделей.

## Потоки

```text
processing Result/Envelope
  -> bounded diagnostics
  -> DiagnosticSink delta
  -> FailurePolicy
  -> ExtractionResult + DiagnosticSummary

runtime action
  -> typed LogEvent / MDC scope
  -> SLF4J
  -> console or daemon ECS JSON

Diagnostic -> LoggingDiagnosticSink -> ordinary typed LogEvent
```

`platform-diagnostics` владеет model/catalog/result/policy/sink contracts;
`platform-observability` — typed logging schema, events и MDC;
`platform-diagnostics-logging` — bridge. Domain не зависит ни от одного из них:
он возвращает pure decision outcomes, а application создаёт diagnostics/TRACE.

## Diagnostic contract

- Stable `DiagnosticCode` задаёт category, default severity, impact, message key
  и default template. Полный актуальный список генерируется в
  [DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md).
- `impact` (`ELEMENT`, `RUN`, `OPERATION`) принадлежит code metadata и не
  выводится из severity.
- Producer передаёт code + structured context, но не форматирует итоговую
  строку. `DiagnosticRenderer` владеет presentation/redaction.
- Pipeline доставляет только delta новой стадии. Одна occurrence либо appended
  в outcome, либо переносится `DiagnosticException`, но не обоими каналами.
- `fail-fast` останавливается на ERROR/FATAL; `collect-and-continue` пропускает
  ERROR и останавливается на FATAL. Budget ограничивает high-cardinality
  occurrences и явно добавляет `PIPELINE.DIAGNOSTICS_SUPPRESSED`.
- Saga diagnostics фиксируют final fact после retries/state transition и не
  заменяют durable ledger status.

Default mapping severity в operational `log.level` является стабильным:

| Severity | Log level |
|---|---|
| `FATAL`, `ERROR` | `ERROR` |
| `WARN` | `WARN` |
| `INFO` | `INFO` |
| `DEBUG` | `DEBUG` |
| `TRACE` | `TRACE` |

Durable occurrence report/quarantine пока отсутствует; authoritative seam —
OBS-4 в `KNOWN-ISSUES.md`. Best-effort logging не выдаётся за durable report.

## Logging contract

- ECS standard fields используются без переименования; project fields живут
  только в `ioc.*`. Полный typed catalog генерируется в
  [LOGGING-CATALOG.md](../LOGGING-CATALOG.md).
- `event.action` является стабильным machine-readable contract; `message` —
  человеческий текст.
- MDC несёт только ambient `STRING` correlation (`ioc.run.id`, source, mode,
  stage). Event-local strings/numbers/booleans идут через SLF4J key/value pairs
  и проверяются `LogField` schema.
- Run id создаёт driving boundary. Daemon использует тот же id, что durable
  `ingest_run`; pipeline не генерирует скрытый fallback.
- Daemon пишет nested ECS JSON rolling file и console; oneshot остаётся
  console-first. Formatter и retention являются bootstrap concerns.
- Logging/diagnostic sink failure observational: non-throwing decorator не
  должен ломать business operation.

## ECS wire representation

`LogField` и ECS vocabulary используют логические dotted paths, но daemon-файл
материализует их как nested JSON objects. Например, логический набор
`ecs.version`, `event.action`, `event.outcome` выглядит так:

```json
{
  "ecs": {"version": "8.11"},
  "event": {
    "action": "app_start",
    "outcome": "success",
    "dataset": "ioc-extractor"
  }
}
```

До Spring Boot 3.5 те же поля сериализовались как flat dotted keys:

```json
{
  "ecs.version": "8.11",
  "event.action": "app_start",
  "event.outcome": "success"
}
```

Поэтому физический путь в consumer queries изменился, хотя логическое имя поля
сохранилось:

| Логическое поле | Старый flat query | Текущий nested query |
|---|---|---|
| `ecs.version` | `jq '.["ecs.version"]'` | `jq '.ecs.version'` |
| `service.name` | `jq '.["service.name"]'` | `jq '.service.name'` |
| `event.action` | `jq '.["event.action"]'` | `jq '.event.action'` |
| `event.outcome` | `jq '.["event.outcome"]'` | `jq '.event.outcome'` |
| `ioc.run.id` | `jq '.["ioc.run.id"]'` | `jq '.ioc.run.id'` |

`event.outcome` существовал до миграции и не является новым полем; изменился
его physical path. Аналогично typed scalar migration меняет JSON type отдельных
значений, но не их логическое имя. Ingest pipelines, jq-скрипты и dashboards
должны учитывать обе оси совместимости: shape и scalar type.

`IocEcsStructuredLogEncoder` не владеет JSON schema: он добавляет статический
`event.dataset` в общий key/value stream, после чего делегирует nested rendering
стандартному Spring Boot formatter-у. Это предотвращает duplicate top-level
`event`. Представительный regression contract находится в
`LogbackConfigurationTest`.

## Sensitive data

1. INFO не перечисляет raw IOC.
2. Raw item допускается только в явно разрешённом DEBUG/TRACE contract; per-item
   structured TRACE требует одновременно config gate и logger TRACE.
3. URL query и credentials санитизируются независимо от log level.
4. INFO/WARN/ERROR/FATAL diagnostic renderer заменяет raw indicator/value/item
   short identity и не выводит входной документ целиком.
5. Transport host/share/username/password не входят в operational fields.

## Корректность и correlation

`PipelineRunner` открывает run scope, затем stage scopes и закрывает их через
`MdcScope`; terminal diagnostics остаются в run scope без ложной последней
стадии. Event-local field при collision временно скрывает одноимённый MDC value,
чтобы ECS JSON не получил duplicate key и scalar type не превратился в string.

Control-event publish/dispatch, keyed executor admission и scheduler outcomes
являются operational events/health signals. Для них не создаётся искусственная
diagnostic category `EVENTS`, потому что они не являются processing outcome.

## Как расширять

- Новый diagnostic code добавлять вместе с production producer; catalog
  reference-ratchet не допускает orphan constants.
- Новый log field/action добавлять в typed enum/catalog и проверять JSON scalar
  type, collision и redaction.
- Новый diagnostic output реализует `DiagnosticSink` adapter; durable report
  требует отдельной occurrence identity/storage модели, а не file appender.
- APM может добавить настоящие ECS `trace.id`/`transaction.id`; обычный run id
  не подменяет tracing semantics.

## Источники истины

- Diagnostic model/catalog: `platform-diagnostics` и generated catalog tests.
- Pipeline delivery: `PipelineRunnerTest`, failure-policy/budget tests.
- Logging schema: `EventAction`, `LogField`, `LogEvent` и
  [LOGGING-CATALOG.md](../LOGGING-CATALOG.md).
- Bridge/redaction: `platform-diagnostics-logging`,
  `SensitiveLogValueSanitizer` and regression tests.
- ECS runtime: `logback-spring.xml`, `IocEcsStructuredLogEncoder`, representative
  JSON contract tests.
- Decision TRACE: `LoggingPipelineDecisionTracer` tests.

## Когда обновлять документ

Обновить при изменении diagnostic delivery/policy/budget, completion mapping,
typed logging schema, correlation ownership, redaction или daemon wire format.
Версии библиотек и полный перечень fields/actions сюда не копируются.

## Связанные документы

- [processing.md](processing.md) — diagnostics до/после commit.
- [event-coordination.md](event-coordination.md) — operational event signals.
- [SECURITY-ENGINEERING.md](../SECURITY-ENGINEERING.md) — logging security control.
- [ADR-0017](../ADR/0017-diagnostics-first-class-outcome.md) и
  [ADR-0018](../ADR/0018-typed-ecs-structured-logging.md) — diagnostic и typed
  logging semantics;
- [ADR-0019](../ADR/0019-spring-boot-4-nested-ecs.md) — Spring Boot baseline и
  physical nested ECS representation.
