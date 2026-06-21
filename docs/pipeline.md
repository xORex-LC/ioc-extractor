# Подход к конвейеру данных (ETL)

Осознанный выбор архитектуры обработки данных, на который мы ориентируемся.

## Выбор

**Pipes-and-Filters (EIP)** как структурная основа + неизменяемый
**Envelope/Message** как носитель данных и метаданных + **Result/Either** на
уровне элемента + **функциональная композиция** стадий.

Почему именно эта связка под наш проект:

| Цель | Что даёт |
|---|---|
| Расширяемость стадий (OCP), config-driven | Pipes-and-Filters: стадия — независимый Filter, добавляется/переставляется без правки соседей |
| Диагностика как метаданные | Envelope несёт `diagnostics` рядом с payload (см. [diagnostics.md](diagnostics.md)) |
| Политика ошибок (collect-and-continue) | Result/Either на элемент + `FailurePolicy` между стадиями |
| Единый путь batch и stream | `SourceUnit` инжеста → тот же Envelope → тот же конвейер ([ingestion.md](ingestion.md)) |
| Изоляция/тестируемость сервисов | каждый Filter = сервис за портом, чистая функция над Envelope |

## Модель

```java
// реализованное тонкое, framework-free ядро (application.pipeline)
record Envelope<T>(T payload, EnvelopeMeta meta, List<Diagnostic> diagnostics) {}
record EnvelopeMeta(String runId, String sourceId, Path sourcePath,
                    StageName stage, Instant createdAt, Map<String,Object> attributes) {}

interface Stage<I, O> {                 // Filter
    StageName name();
    Envelope<O> process(Envelope<I> in);
}

record Result<T>(T value, List<Diagnostic> diagnostics) {}   // per-item/стадийный исход
```

- **Envelope** неизменяем; стадия возвращает новый Envelope, обогащая `meta`/
  `diagnostics`. Payload — доменные данные (text → RawIndicator[] → Indicator[] → rows),
  типизирован payload-record-ами (`SourceText`, `RefangedText`, … `ArtifactWriteSummary`).
- **Stage** — чистый Filter; порядок собирает `Pipeline.start().then(s1).then(s2)…`,
  исполняет `PipelineRunner` и применяет `FailurePolicy` после каждой стадии
  (`Notification.throwIfRejected` бросает `DiagnosticException` при stop).
- **Result** — тонкий `record` (value + diagnostics) для пер-элементных исходов
  внутри стадии; накопленные диагностики живут в Envelope (один источник истины).
- **FailurePolicy** (Strategy): `fail-fast | collect-and-continue` — между стадиями.
- **PipelineObserver** (port) — framework-free seam наблюдаемости: `PipelineRunner`
  открывает stage-scope и шлёт `stageStarted/Completed/Failed` через порт. ECS/MDC
  реализация (`LoggingPipelineObserver`) живёт в `observability`; ядро конвейера
  остаётся без logging-зависимостей (default — `NoopPipelineObserver`).

## Стадии конвейера

```
read → refang → extract → attribute → deduplicate → fill → sink
 (каждая — Stage/Filter; данные и метаданные идут в Envelope)
```

Соответствие сервисам — в [services.md](services.md). Оркестрацию собирает
`ExtractIocsUseCase` (application): порядок стадий и `FailurePolicy`
конфигурируемы; стадии — порты, реализации внедряются в composition root.

## Границы и фреймворки

- **Ядро конвейера — framework-free**: свой тонкий `Stage`/`Envelope`/`Result`
  в `application`/`domain`. Никакого pipeline-фреймворка в ядре (KISS, чистый
  гексагон).
- **EIP-фреймворк только на крае инжеста**: Spring Integration реализует
  Pipes-and-Filters/Message для приёма файлов ([ingestion.md](ingestion.md)) и
  отдаёт `SourceUnit` в наш конвейер через driving-порт `IngestSourceUseCase`
  (`SourceFeed` — adapter-local над SI).
- **Либы Result/Either** — берём **свой тонкий `Result`** (без зависимости);
  Vavr (`Either`/`Try`) — опционально, если понадобится богатый набор.

## Эволюция

**Реализовано (этап 7):** `IocExtractionService` стал тонким orchestrator-ом —
строит initial `Envelope`, запускает `PipelineRunner`, маппит финальный payload в
`ExtractionResult`. Шаги вынесены в `Stage`-ы (`application.pipeline.stage`), порядок
— в `Pipeline`, диагностики копятся в `Envelope`. Output не изменился
(`GoldenPipelineTest`). Доменные сервисы не тронуты (они уже за портами). Шаг по
[modularization.md](modularization.md): стадии живут в `ioc-application`,
носитель/`Result` — кандидаты в `platform-*`.

## Референсы

- **Enterprise Integration Patterns** (Hohpe & Woolf) — Pipes and Filters, Message,
  Message Envelope.
- **Apache Camel / Spring Integration** — реализации EIP (используем SI на инжесте).
- **Railway-Oriented Programming / Result-Either** — функциональная обработка
  ошибок как данных; **Vavr** (`Either`/`Try`) — опц. библиотека.
- **M. Fowler — Collecting Parameter / Notification** — накопление диагностик.
