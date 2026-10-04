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
| `ReadSourceStage.java` | `SourceReader` → `SourceText`; `SOURCE.EMPTY_TEXT` через envelope |
| `RefangStage.java` | `Refanger` → `RefangedText` |
| `ExtractIndicatorsStage.java` | `IndicatorExtractor` → `ExtractedIndicators` + overlap diagnostics |
| `AttributeSourceStage.java` | `SourceAttributor` → `AttributedIndicators` |
| `PrepareRoutedArtifactsStage.java` | Обязательный план документа; учёт исходных наблюдений, routing/classification/mapping diagnostics и выбор по конечному ключу согласно плану |
| `WriteArtifactsStage.java` | deferred-id materialization, canonical commit, projection / dry-run summary |

## Зависимости

**Зависит от:** `application.pipeline`, application out-ports, domain services.

**Не импортируется:** соседние stage implementations, adapters, bootstrap,
Spring, Logback/MDC.

## TRACE-контракт

Per-item TRACE идёт через application-owned `PipelineDecisionTracer`: каждая
стадия сначала проверяет затвор адаптера и затем строит compact decision из уже
рассчитанного outcome. Повторный вызов domain services ради лога запрещён.

Обычная diagnostic occurrence использует ровно один путь: stage либо прикрепляет
её к возвращаемому envelope, либо бросает typed `DiagnosticException`, если
валидный payload вернуть невозможно.

Extraction и document preparation ограничивают diagnostic detail внутри циклов
через per-invocation `BoundedDiagnosticCollector`. Его `DiagnosticBatch`
передаёт точные severity counts в envelope отдельно от retained samples;
поздние ERROR/FATAL достигают checkpoint даже при исчерпанном лимите.
