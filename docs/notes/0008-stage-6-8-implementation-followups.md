# 0008 — Ревью реализации этапов 6–8 и технический долг

## Статус

**Принято.** Этапы 6 (diagnostics), 7 (pipeline/Envelope) и 8 (observability)
реализованы и зелёные (`mvn test`, 116 тестов, golden без изменений). Эта заметка
фиксирует итоги code review: что поправлено сразу и какой долг осознанно отложен —
чтобы не потерять.

## Контекст

Реализация шла по планам [0006](../plan/0006-diagnostics-implementation-plan.md),
[0007](../plan/0007-pipeline-envelope-implementation-plan.md),
[0008](../plan/0008-logging-observability-implementation-plan.md). Архитектура
выдержана: `diagnostics` без SLF4J/Logback, ядро pipeline framework-free,
observability подключён через порт `PipelineObserver`, три подсистемы разделены
(подтверждено ArchUnit). Ниже — точечные находки.

## Устранено сразу (в рамках ревью)

| # | Находка | Фикс |
|---|---|---|
| F1 | `DiagnosticSink`-бин создавался, но никем не потреблялся (мёртвая проводка) | `IocExtractionService` дренирует `Envelope.diagnostics()` в `DiagnosticSink` в конце прогона; бин прокинут из `AppConfig`; дефолт — `NoopDiagnosticSink` |
| F2 | CLI хардкодил `ioc.mode = oneshot` | `ExtractCommand` берёт режим из `@Value("${ioc.observability.mode:oneshot}")` (без импорта `bootstrap` — слой цел) |
| F3 | `DiagnosticException` нёс как message только `code.id()` | message = `id + context` |
| F4 | Спекулятивный `DiagnosticCategory.VALIDATION` без кодов | удалён (YAGNI) |
| F5 | `PipelineRunner` redundant-catch читался как dead code | прокомментировано: внешние `catch` относятся только к `close()` |
| F6 | `platform-observability` зависел на `application.pipeline` через `LoggingPipelineObserver` | на этапе 9 reusable ETL-контракты вынесены в `platform-etl` (`com.iocextractor.platform.etl`); observability больше не зависит на `ioc-application` |

## Отложенный долг (открыто)

| # | Долг | Почему отложено | Куда |
|---|---|---|---|
| D1 | **Диагностика не интегрирована в реальный поток.** Нет ни одного producer-а `Diagnostic`: адаптеры/стадии бросают `IocExtractorException`. Следствие — `FailurePolicy` отрабатывает вхолостую, `DiagnosticSink` дренируется пустым. | Планы 6/7 явно отложили «массовую замену исключений на `Result`»; producer-ы вводятся «по мере появления реальных ошибок». Seam уже живой (F1). | следующий под-этап: первые producer-ы (`SOURCE.READ_FAILED`, `SINK.WRITE_FAILED`, `PIPELINE.ITEM_SKIPPED`/dedup) |
| D3 | **ECS-типы `event.duration` / `ioc.rows` — строки.** Идут через MDC, а SLF4J MDC хранит только `String`; `logback-ecs-encoder` сериализует их как строки, тогда как ECS типизирует `event.duration` как `long`. Риск mapping-конфликта в Elasticsearch. | Числовые per-event поля через MDC чисто не сделать; форсить костыль не стали. | при подключении внешнего collector / строгого ECS-маппинга |
| D4 | `DiagnosticCatalogTest` сканирует `codes/` и ассертит protocol `file` — сломается, если тесты однажды поедут из jar. | Сейчас не воспроизводится (тесты гоняются из `target/classes`). | при упаковке тестов в jar |

## Следствия

- Отражено в доке: [diagnostics.md](../diagnostics.md) (статус этапов внедрения,
  `TemplateDiagnosticRenderer` вместо MessageSource, `MessageCatalog`/i18n отложены,
  баннер про D1), [pipeline.md](../pipeline.md) (реальный `Result`-record, `Pipeline`/
  `PipelineRunner`/`PipelineObserver`, этап 7 как realized), [logging.md](../logging.md)
  (актуальный API `MdcScope.open().put(...)`).
- `event.dataset` статический (`ioc-extractor`) — уже синхронизировано в
  logging.md/logging-taxonomy.md (encoder фильтрует reserved ECS keys из MDC).

## Открытые вопросы

- Когда вводить D1: отдельным малым под-этапом до модуляризации или вместе с
  первым реальным источником ошибок (инжест, этап 10)?
