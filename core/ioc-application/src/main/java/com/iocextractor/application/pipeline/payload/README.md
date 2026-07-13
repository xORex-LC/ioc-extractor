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
| `RefangedText.java` | `RefangOutcome`: текст + применённые правила |
| `ExtractedIndicators.java` | Refanged text + `ExtractionOutcome` |
| `AttributedIndicators.java` | `AttributionOutcome` после source attribution |
| `DeduplicatedIndicators.java` | Исходный count + indicators после optional batch dedup |
| `ClassifiedIndicator.java` | Indicator + единственный materialized `ClassificationDecision` |
| `RetainedIndicators.java` | Исходный count + classified indicators для artifact preparation |
| `PreparedArtifacts.java` | Artifact write plans после mapping и до policy/commit |
| `ArtifactWriteSummary.java` | Итог записи артефактов |

## Зависимости

**Зависит от:** domain model/extract types.

**Не импортируется:** adapter/bootstrap/Spring/logging.
