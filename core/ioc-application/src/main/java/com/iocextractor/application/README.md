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
| `port/in/` | Driving-порты: extraction, ingest, maintenance и artifact export/recovery use cases |
| `port/out/` | Driven-порты: source/sink/lookup, ingest, canonical storage и streaming artifact export |
| `artifact/` | Storage-neutral artifact row identity, canonical artifact snapshots and run-ledger recovery model |
| `export/` | Artifact Emission model: resolved plan, snapshot/manifest, formation saga, progress и retention descriptors |
| `cadence/` | Framework-free interval/quiet-period scheduling policies с injected `Clock` |
| `pipeline/` | `Envelope`/`Stage`/`PipelineRunner` и concrete stages текущего ETL |
| `service/` | `IocExtractionService` — use-case boundary и запуск pipeline |

## Зависимости

**Зависит от:** `domain`, `diagnostics`. **Не импортируется** адаптерами иначе
как через порты и use-case boundary.
