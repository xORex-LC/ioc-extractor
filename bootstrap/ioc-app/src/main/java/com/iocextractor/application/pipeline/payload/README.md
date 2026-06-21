# com.iocextractor.application.pipeline.payload

## Назначение

Типизированные payload records, которыми обмениваются стадии ETL-конвейера.
Они делают контракт между стадиями явным и защищают pipeline от передачи
неструктурированных `Object`/`Map`.

**Правило слоя:** payload records не выполняют бизнес-логику и не знают о
pipeline runner, logging или adapters. Коллекции и map копируются при входе.

## Структура

| Файл | Назначение |
|---|---|
| `SourceText.java` | Текст, прочитанный из источника |
| `RefangedText.java` | Текст после refang |
| `ExtractedIndicators.java` | Refanged text + raw indicators |
| `AttributedIndicators.java` | Indicators после source attribution |
| `RetainedIndicators.java` | Indicators до/после dedup |
| `ArtifactWriteSummary.java` | Итог записи артефактов |

## Зависимости

**Зависит от:** domain model/extract types.

**Не импортируется:** adapter/bootstrap/Spring/logging.
