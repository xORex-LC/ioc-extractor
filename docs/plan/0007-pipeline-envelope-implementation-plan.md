# План реализации этапа 7: Pipeline / Envelope

> Актуализация после этапа 9: reusable ETL-контракты вынесены из
> `application.pipeline` в `platform/platform-etl`
> (`com.iocextractor.platform.etl`). `StageName` заменён на generic `StageId`;
> `EnvelopeMeta` хранит `runId`, `sourceId`, `stage`, `createdAt` и
> `attributes`, а IOC-specific поля (`ioc.source.path`, `ioc.mode`,
> `ioc.dry_run`) задаются application metadata keys. Исторические упоминания
> `StageName`, `sourcePath` и `application.pipeline core` ниже относятся к
> реализации этапа 7 до модульного рефакторинга.

## Цель

Эволюционировать текущий линейный `IocExtractionService` в явный ETL-конвейер
по модели [pipeline.md](../pipeline.md): Pipes-and-Filters + immutable
`Envelope`/`Meta` + diagnostics as metadata + `FailurePolicy` между стадиями.

Главный результат этапа: каждый шаг `read → refang → extract → attribute →
deduplicate → sink` становится отдельной `Stage`, а `IocExtractionService`
остаётся тонким use-case orchestrator, который собирает и запускает pipeline.
Поведение и выходные CSV-артефакты не должны измениться.

## Предусловия

- Этап 6 diagnostics реализован: доступны `Diagnostic`, `Result`,
  `Notification`, `FailurePolicy`, `DiagnosticSink`.
- `GoldenPipelineTest` зелёный и считается основным регрессионным guardrail для
  неизменности output.
- Текущая реализация находится в `IocExtractionService`: чтение, refang,
  extraction, attribution, dedup и sink-запись идут в одном методе.

## Границы этапа

Входит в этап:

- `Envelope<T>` и `EnvelopeMeta` как immutable носители payload + metadata +
  diagnostics;
- `Stage<I,O>` и pipeline runner/composition;
- stage-классы для текущих шагов ETL;
- перенос приватной orchestration-логики из `IocExtractionService` в стадии;
- применение `FailurePolicy` после стадий;
- сохранение текущего `ExtractIocsUseCase` API;
- unit-тесты Envelope/Stage/Pipeline и stage-классов;
- golden/e2e проверка неизменности output.

Не входит в этап:

- ECS/MDC/`MdcScope`/structured logging — это этап 8;
- daemon ingestion, partitions, ledger, retry/dead-letter storage — это этап 10;
- переход на Spring Integration/Camel внутри application core;
- перестройка CSV sinks/output mapping;
- полноценный metrics/tracing/APM;
- массовая доменная миграция dedup в отдельный domain service, если это ломает
  фокус этапа.

## Карта ответственности

| Уровень | Обязан делать | Запрещено делать |
|---|---|---|
| `application.pipeline` core | хранить `Envelope`, `EnvelopeMeta`, `Stage`, runner, immutable pipeline contracts | импортировать Spring, Tika, CSV, picocli, Logback/MDC |
| stage-классы | адаптировать один шаг use-case к `Stage` contract | знать о соседних стадиях, appender/logging policy, CLI |
| domain services | выполнять бизнес-правила: refang, extract, attribute, classify | знать про `Envelope`, Spring, sinks |
| application service | собрать pipeline, выбрать `FailurePolicy`, вернуть `ExtractionResult` | содержать детали каждого шага алгоритма |
| adapters/sinks | читать/писать через существующие ports | зависеть на конкретные stage implementations |

## Целевая структура файлов

```text
src/main/java/com/iocextractor/application/pipeline/
├── README.md
├── package-info.java
├── Envelope.java
├── EnvelopeMeta.java
├── Pipeline.java
├── PipelineRunner.java
├── Stage.java
├── StageName.java
└── StageExecutionException.java

src/main/java/com/iocextractor/application/pipeline/payload/
├── README.md
├── package-info.java
├── SourceText.java
├── RefangedText.java
├── ExtractedIndicators.java
├── AttributedIndicators.java
├── RetainedIndicators.java
└── ArtifactWriteSummary.java

src/main/java/com/iocextractor/application/pipeline/stage/
├── README.md
├── package-info.java
├── ReadSourceStage.java
├── RefangStage.java
├── ExtractIndicatorsStage.java
├── AttributeSourceStage.java
├── DeduplicateIndicatorsStage.java
└── WriteArtifactsStage.java

src/test/java/com/iocextractor/application/pipeline/
├── EnvelopeTest.java
├── PipelineRunnerTest.java
└── StageContractTest.java

src/test/java/com/iocextractor/application/pipeline/stage/
├── ReadSourceStageTest.java
├── RefangStageTest.java
├── ExtractIndicatorsStageTest.java
├── AttributeSourceStageTest.java
├── DeduplicateIndicatorsStageTest.java
└── WriteArtifactsStageTest.java
```

`IocExtractionService` остаётся в `application/service`, но становится тонким:
создаёт initial envelope, запускает `Pipeline`, маппит финальный payload в
`ExtractionResult`.

## Контракты типов

### `Envelope<T>`

Immutable value object:

```java
public record Envelope<T>(
        T payload,
        EnvelopeMeta meta,
        List<Diagnostic> diagnostics
) {}
```

Правила:

- `payload` может быть `null` только если диагностики объясняют отсутствие
  значения; для штатных стадий payload non-null;
- `meta` обязателен;
- `diagnostics` defensively copied (`List.copyOf`);
- helpers возвращают новый envelope: `withPayload`, `atStage`,
  `withDiagnostic`, `withDiagnostics`, `withMetaAttribute`;
- stage не мутирует входной envelope.

### `EnvelopeMeta`

Минимальный источник истины для будущего logging/MDC:

```java
public record EnvelopeMeta(
        String runId,
        String sourceId,
        Path sourcePath,
        StageName stage,
        Instant createdAt,
        Map<String, Object> attributes
) {}
```

Правила:

- `runId` создаётся один раз на use-case boundary;
- `sourceId` на этапе 7 можно вычислять просто и стабильно из source path
  (`sourcePath.toAbsolutePath().normalize().toString()`), content hash будет
  добавлен позже в ingest/observability;
- `stage` обновляется через `Envelope.atStage(...)`;
- `createdAt`/timestamps создаются через `Clock`, не через `Instant.now()` в
  модели;
- `attributes` — extension map для будущих counters/technical metadata, но не
  dumping ground для payload.

### `Stage<I,O>`

Рекомендуемый контракт:

```java
public interface Stage<I, O> {
    StageName name();
    Envelope<O> process(Envelope<I> input);
}
```

Обоснование: `Envelope` уже несёт diagnostics, а `Result<T>` из diagnostics
используется внутри стадий для per-item исходов. Это проще, чем
`Result<Envelope<O>>`, и не создаёт двойного места хранения diagnostics.

Правила:

- stage делает один шаг;
- stage возвращает новый envelope с новым payload;
- stage не вызывает соседние stages;
- stage не пишет в лог и не знает о MDC;
- stage может добавить diagnostics, но не форматирует их.

### `PipelineRunner`

`PipelineRunner` последовательно применяет stages и после каждой стадии проверяет
накопленные diagnostics через `FailurePolicy`.

```java
Envelope<O> run(Envelope<I> input, Pipeline<I, O> pipeline);
```

Если `FailurePolicy` возвращает stop, boundary бросает `DiagnosticException`
через `Notification.throwIfRejected(policy)`.

### `StageName`

Stable machine-readable names:

- `READ_SOURCE`;
- `REFANG`;
- `EXTRACT`;
- `ATTRIBUTE`;
- `DEDUPLICATE`;
- `WRITE_ARTIFACTS`.

Имена позже маппятся в `ioc.stage` для observability, поэтому менять их после
публикации нельзя без migration note.

## Payload records

Payload records нужны, чтобы не передавать между стадиями «голые» `String`/`List`
с неясным смыслом.

| Payload | Поля | Назначение |
|---|---|---|
| `SourceText` | `String text` | результат `SourceReader` |
| `RefangedText` | `String text` | текст после refang |
| `ExtractedIndicators` | `String text`, `List<RawIndicator> rawIndicators` | refanged text + raw matches для attribution |
| `AttributedIndicators` | `List<Indicator> indicators` | индикаторы с source attribution |
| `RetainedIndicators` | `List<Indicator> extracted`, `List<Indicator> retained` | до/после dedup для summary |
| `ArtifactWriteSummary` | `int extracted`, `int retained`, `Map<String,Integer> writtenPerArtifact` | финальный payload для `ExtractionResult` |

Все records defensively copy collections/maps.

## Stage responsibilities

| Stage | Input → Output | Dependencies | Diagnostics |
|---|---|---|---|
| `ReadSourceStage` | `ExtractionCommand` → `SourceText` | `SourceReader` | `SOURCE.READ_FAILED`, `SOURCE.EMPTY_TEXT` |
| `RefangStage` | `SourceText` → `RefangedText` | `Refanger` | optional `PIPELINE.STAGE_FAILED` on unexpected failure |
| `ExtractIndicatorsStage` | `RefangedText` → `ExtractedIndicators` | `IndicatorExtractor` | extraction failures/skips later |
| `AttributeSourceStage` | `ExtractedIndicators` → `AttributedIndicators` | `SourceAttributor` | attribution failures later |
| `DeduplicateIndicatorsStage` | `AttributedIndicators` → `RetainedIndicators` | `LookupRepository`, `deduplicate` flag | duplicate/lookup failure diagnostics later |
| `WriteArtifactsStage` | `RetainedIndicators` → `ArtifactWriteSummary` | `List<IocSink>` | `SINK.WRITE_FAILED`, dry-run summary |

Для stage 7 не нужно насильно покрывать каждый recoverable branch diagnostic-ом.
Главное — pipeline умеет переносить diagnostics как metadata и применять
`FailurePolicy`. Расширять конкретные producer diagnostics можно по мере появления
реальных ошибок.

## Интеграция с текущим `IocExtractionService`

Текущий метод:

```text
read → refang → extract → attribute → deduplicate → write sinks → ExtractionResult
```

После этапа:

```text
ExtractionCommand
  → Envelope<ExtractionCommand>
  → PipelineRunner
  → Envelope<ArtifactWriteSummary>
  → ExtractionResult
```

Правила интеграции:

- public API `ExtractIocsUseCase.extract(ExtractionCommand)` не меняется;
- `ExtractionResult` остаётся совместимым;
- `dryRun` семантика не меняется;
- `writtenPerArtifact` order остаётся `LinkedHashMap`/stable insertion order;
- output artifacts golden не меняются;
- существующие INFO-логи можно оставить в service временно, но новые stage-классы
  не должны расширять logging до observability этапа.

## FailurePolicy / diagnostics flow

Минимальная схема:

1. Stage возвращает `Envelope<O>` с накопленными diagnostics.
2. `PipelineRunner` после стадии строит `Notification` из diagnostics envelope.
3. `FailurePolicy` чисто возвращает decision.
4. Если decision = stop, runner бросает `DiagnosticException` на orchestration
   boundary.
5. Если decision = continue, pipeline идёт дальше.

На этапе 7 default policy можно задать как `FailurePolicy.failFast()` или
`collectAndContinue()` в `IocExtractionService` constructor. Если policy будет
конфигурироваться из `application.yml`, это отдельный маленький wiring-шаг через
`IocProperties`, без бизнес-логики в config parser.

## Dedup boundary

В текущем коде dedup — private method в `IocExtractionService`: within-batch
`dedupKey()` + lookup против `LookupRepository`.

На этапе 7 переносим эту логику в `DeduplicateIndicatorsStage`, потому что она
зависит от application out-port `LookupRepository`. Не выносим её в domain
service целиком, чтобы domain не зависел от application ports. Позже можно
отделить чистую within-batch часть в domain service, если она начнёт расти.

## Тестовый контур

### Model conformance: Pipes-and-Filters

Отдельно проверяем не только результат обработки, но и соответствие выбранной
модели Pipes-and-Filters. Эти проверки должны падать, если код вернулся к
«большому оркестратору» или stages начали напрямую вызывать друг друга.

Проверки модели:

- все concrete stages в `..application.pipeline.stage..` реализуют
  `Stage<I,O>`;
- каждый stage имеет ровно один стабильный `StageName`;
- stage-классы не зависят друг от друга напрямую;
- stage-классы не зависят от `Pipeline`, `PipelineRunner` и composition root;
- `PipelineRunner`/`Pipeline` — единственное место, где задаётся порядок стадий;
- stage method принимает и возвращает только `Envelope`, не side-channel state;
- `Envelope` immutable: stage возвращает новый envelope и не мутирует входной;
- stage не импортирует logging/MDC/ECS, Spring, adapter implementations;
- payload между стадиями типизирован record-ами, а не `Map<String,Object>` или
  необъяснимыми `Object`.

Часть проверок — ArchUnit, часть — unit contract tests:

- `PipelineModelArchitectureTest`: зависимости stage→stage запрещены; framework
  imports запрещены; concrete stages implement `Stage`;
- `StageContractTest`: stage не мутирует входной envelope и сохраняет
  diagnostics/meta;
- `PipelineRunnerTest`: порядок исполнения определяется списком stages и
  меняется только через `Pipeline`;
- `PayloadTypeTest`: payload records defensively copy collections/maps.

### Unit tests

- `EnvelopeTest`: immutable diagnostics/attrs, `withPayload`, `atStage`,
  diagnostics merge, Clock-controlled metadata;
- `PipelineRunnerTest`: order of stages, diagnostics propagation,
  fail-fast/collect-and-continue behavior;
- `StageContractTest`: stage does not mutate input envelope;
- stage tests с fake ports, без Spring context:
  - source read;
  - refang;
  - extraction;
  - attribution;
  - dedup enabled/disabled/lookup hit;
  - dry-run write summary;
  - sink write order and counts.

### Integration / regression

- `GoldenPipelineTest` остаётся главным e2e: output masks/hashes не меняются;
- `ApplicationContextTest` проверяет wiring после constructor changes;
- при необходимости добавить focused `IocExtractionServicePipelineTest` с
  synthetic fake stages/ports, без `source/*` и `dataframe/*`.

### Architecture tests

Расширить `ArchitectureTest`:

- `..application.pipeline..` не зависит от Spring, picocli, Tika, commons-csv,
  Logback/MDC;
- `..domain..` не зависит от `..application.pipeline..`;
- stage implementations не зависят друг на друга напрямую, только runner/pipeline
  знает порядок;
- adapters не зависят от concrete stage classes.
- concrete stage classes реализуют `Stage`;
- только `Pipeline`/`PipelineRunner`/`IocExtractionService` могут зависеть сразу
  от нескольких concrete stages.

## CI-контур

Обязательные проверки:

```bash
./mvnw test
./mvnw -B -ntp verify
git diff --check
```

`./mvnw verify` должен включать:

- pipeline model conformance tests;
- ArchUnit rules для Pipes-and-Filters boundaries;
- `GoldenPipelineTest` на синтетическом corpus;
- unit-тесты stage-классов с fake ports.

В GitHub Actions отдельный step можно назвать `Pipeline model and regression`,
даже если технически он запускает тот же Maven lifecycle. Если pipeline
регрессия падает, полезно публиковать generated `target/golden/*.csv` как
workflow artifacts для анализа diff.

Запрещено:

- читать production `source/*`, `dataframe/*`, `configs/*` в новых unit-тестах;
- писать в project `dataframe/`;
- менять golden expected files без явной причины и review;
- использовать текущую дату без `Clock`;
- вводить новые внешние pipeline frameworks в core.

## Порядок реализации

1. Создать `application/pipeline` + README/package-info.
2. Реализовать `StageName`, `EnvelopeMeta`, `Envelope`.
3. Реализовать `Stage`, `Pipeline`, `PipelineRunner`.
4. Добавить unit-тесты pipeline core.
5. Создать `application/pipeline/payload` records с defensive copies.
6. Создать stage package и перенести шаги из `IocExtractionService` в stage
   classes.
7. Реализовать `DeduplicateIndicatorsStage` с текущей логикой dedup.
8. Реализовать `WriteArtifactsStage` с сохранением `dryRun` behavior.
9. Переписать `IocExtractionService` как тонкий orchestrator.
10. Обновить `bootstrap/AppConfig`, если constructor dependencies изменятся.
11. Обновить README в `application/` и `application/service/`.
12. Добавить/расширить ArchUnit rules.
13. Запустить unit/golden/full verify.
14. Провести check-up side effects: output diff, stage boundaries, diagnostics
    propagation, no observability leakage.

## Критерии приёмки

- `Stage`/`Envelope`/`PipelineRunner` реализованы и покрыты тестами;
- есть проверки соответствия модели Pipes-and-Filters: stage independence,
  single ordering owner, immutable Envelope;
- текущий output не изменился (`GoldenPipelineTest` зелёный);
- `IocExtractionService` больше не содержит тело всего алгоритма;
- dedup behavior совпадает с текущим;
- dry-run не пишет artifacts и возвращает пустой/ожидаемый `writtenPerArtifact`;
- `FailurePolicy` реально применяется между стадиями;
- diagnostics переносятся в envelope metadata;
- pipeline core framework-free;
- observability/MDC/ECS не появились в stage 7.

## Риски и решения

| Риск | Решение |
|---|---|
| Pipeline abstraction станет тяжелее текущей задачи | Держать `Stage`/`Envelope` минимальными, без DSL/framework |
| Дублирование diagnostics между `Envelope` и `Result` | `Envelope` — накопленное состояние pipeline; `Result` — per-item/stage-local helper |
| Stage начнут знать друг о друге | Порядок живёт только в `Pipeline`/runner/composition root |
| Изменится CSV output | Golden e2e + optional diff before/after на synthetic fixture |
| Dedup переедет в неверный слой | Пока оставить lookup-aware dedup в application stage; domain не знает `LookupRepository` |
| Observability начнёт внедряться раньше времени | Stage 7 не добавляет `MdcScope`, ECS, `LoggingDiagnosticSink` |

## Проверка после реализации

```bash
./mvnw test
./mvnw -B -ntp verify
git diff --check
```

Опциональная ручная проверка полного источника:

```bash
./mvnw -q -DskipTests package
java -jar target/ioc-extractor-0.1.0-SNAPSHOT.jar extract --source source/ioc-source.htm
```

Сравнить количество строк и отсутствие неожиданных изменений в generated
artifacts относительно текущего поведения.
