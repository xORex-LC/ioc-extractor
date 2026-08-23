# com.iocextractor.application.classification

## Назначение

Переиспользуемая application-level классификация IOC без зависимости от
extraction pipeline. Обычный ingest и dataframe import должны получать
одинаковый `ClassificationDecision` через этот пакет.

**Правило слоя:** пакет зависит только от domain-контрактов и не знает о Spring,
CSV, JDBC или конкретном use case.

## Структура

| Файл | Назначение |
|---|---|
| `IndicatorClassifier.java` | Единая классификация network/file IOC поверх configured `MatchPolicy` |

## Границы

- network IOC классифицируются существующей декларативной политикой;
- file IOC получают нейтральное решение для downstream mapping;
- diagnostics, tracing и batch orchestration остаются у вызывающего use case.
