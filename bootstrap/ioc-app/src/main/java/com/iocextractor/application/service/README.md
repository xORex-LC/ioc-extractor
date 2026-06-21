# com.iocextractor.application.service

## Назначение

Реализация use-case boundary: создаёт начальный `Envelope`, запускает
`PipelineRunner` и маппит финальный payload в `ExtractionResult`.

**Правило слоя:** зависит на `domain`, application ports и
`application.pipeline`; не знает о конкретных адаптерах и Spring wiring
(собирается в `bootstrap`).

## Структура

| Файл | Назначение |
|---|---|
| `IocExtractionService.java` | Use-case boundary: `ExtractionCommand` → pipeline → `ExtractionResult` |

## Заметки

Детали шагов живут в `application.pipeline.stage`. Сервис остаётся владельцем
use-case API, `runId`/`sourceId` и финального summary, но не содержит тело
алгоритма стадий.
