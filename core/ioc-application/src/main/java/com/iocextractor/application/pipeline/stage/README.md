# com.iocextractor.application.pipeline.stage

## Назначение

Concrete stages текущего ETL-конвейера. Каждый класс адаптирует один шаг
use-case к контракту `Stage<I,O>`.

**Правило слоя:** stage делает один шаг, не вызывает соседние stages и не знает
порядок pipeline. Порядок задают `Pipeline`/`PipelineRunner`/use-case
composition.

## Структура

| Файл | Назначение |
|---|---|
| `ReadSourceStage.java` | `SourceReader` → `SourceText` |
| `RefangStage.java` | `Refanger` → `RefangedText` |
| `ExtractIndicatorsStage.java` | `IndicatorExtractor` → `ExtractedIndicators` |
| `AttributeSourceStage.java` | `SourceAttributor` → `AttributedIndicators` |
| `DeduplicateIndicatorsStage.java` | within-batch dedup by `Indicator.dedupKey()` |
| `ClassifyIndicatorsStage.java` | one materialized `ClassificationDecision` per retained indicator; rule evaluation only for NETWORK |
| `PrepareArtifactsStage.java` | side-effect-free artifact routing/mapping и сбор element diagnostics |
| `WriteArtifactsStage.java` | deferred-id materialization, canonical commit, projection / dry-run summary |

## Зависимости

**Зависит от:** `application.pipeline`, application out-ports, domain services.

**Не импортируется:** соседние stage implementations, adapters, bootstrap,
Spring, Logback/MDC.
