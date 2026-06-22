# com.iocextractor.application

## Назначение

Прикладной слой: порты (driving/driven), модель ETL-конвейера и use-case,
оркестрирующий извлечение. Описывает *что* делает приложение, не зная *как*
(через порты).

**Правило слоя:** зависит на `domain` и framework-free platform contracts
(`diagnostics`). Не зависит на адаптеры, Spring и `bootstrap`. Реализации портов
внедряются снаружи (composition root).

## Структура

| Подпапка | Назначение |
|---|---|
| `port/in/` | Driving-порты: `ExtractIocsUseCase`, `AggregatePartitionsUseCase`, ingest use cases, команды и результаты |
| `port/out/` | Driven-порты: `SourceReader`, `IocSink`, `LookupRepository`, ingest и aggregation storage ports |
| `aggregation/` | Storage-neutral daemon aggregation orchestration, artifact row model, merge policy |
| `pipeline/` | `Envelope`/`Stage`/`PipelineRunner` и concrete stages текущего ETL |
| `service/` | `IocExtractionService` — use-case boundary и запуск pipeline |

## Зависимости

**Зависит от:** `domain`, `diagnostics`. **Не импортируется** адаптерами иначе
как через порты и use-case boundary.
