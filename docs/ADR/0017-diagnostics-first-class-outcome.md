# 0017 — Диагностика как first-class результат обработки (блок OBS-D1)

## Статус

**Принято 2026-07-12, реализуется.** Срезы 1–3 реализованы
(`354c5c6..3d2d1d0`), срезы 4–10 остаются планом. Закрывает **OBS-D1** из
[KNOWN-ISSUES](../KNOWN-ISSUES.md) и задаёт контракт, на котором строится
**OBS-2** (`SINK.CHARSET_UNMAPPABLE`). Является superseding-надстройкой над
разделом «Открытый долг» [dev/DIAGNOSTICS.md](../dev/DIAGNOSTICS.md) и планом
интеграции из [0008](0008-stage-6-8-implementation-followups.md): механизм
диагностики, введённый [0003](0003-diagnostics.md), получает **границу
применения** — что обязано быть `Diagnostic`, что остаётся исключением, кто
решает «продолжать ли», как качество завершения возвращается вызывающей стороне
и по какому каналу диагностика доезжает до
[наблюдаемости 0007](0007-logging-observability.md).

Подготовительный «контрактный проход» блока OBS (OBS-1, OBS-3, CODE-4 —
generated `LOGGING-CATALOG.md`, пин severity→level, reference-ratchet) уже
реализован отдельно (`752d186..aca604e`) и ADR не требовал: там применялись
уже принятые правила. Данный ADR фиксирует именно **решения**.

Дизайн-диалог: `docs/worknote/observability-hardening.md`
(локальная рабочая память итерации, не часть published ADR).

## Контекст и проблема

Ядро `platform-diagnostics` реализовано с этапа 6 (0003): `Diagnostic` +
builder + `DiagnosticFactory`, каталоги кодов, `Result`/`Notification`, чистая
`FailurePolicy` (`fail-fast` | `collect-and-continue`), порты
`DiagnosticSink`/`DiagnosticRenderer`, `DiagnosticException`, мост в ECS-логи
(`platform-diagnostics-logging`). Механизм жизнеспособен, но **не включён на
полную и не доведён до внешнего outcome use case**:

1. **`collect-and-continue` мёртв.** Политика захардкожена:
   `new PipelineRunner(FailurePolicy.failFast(), …)` в
   `IocExtractionService`; в конфиге `ioc.pipeline` есть только
   `deduplicate`. Ни одного production-использования `collectAndContinue()`.
2. **Диагностики теряются при аварии.** Эмиссия в `DiagnosticSink` — одна
   строка в `IocExtractionService.extract(...)` **после** `runner.run(...)`;
   накопленный конверт локален раннеру, поэтому при падении стадии всё
   собранное (включая `SOURCE.MARKERS_UNMATCHED`) не эмитится вовсе.
3. **24 из 43 кодов каталога — без production-ссылки** (baseline запинен
   `CatalogReferenceRatchetTest`, `2681c4d`): категории EXTRACTION, CLASSIFY,
   PIPELINE, SINK — 0 продьюсеров; published карта ошибок обещает коды,
   которых нет в рантайме. ~30 throw-сайтов `IocExtractorException` в
   adapter-sink-csv/adapter-ingest/adapter-store-jdbc/adapter-source-tika.
4. **Де-факто сложились два контура с разной моделью ошибок.** Линии
   0011–0013 (export/sync) уже эмитят typed diagnostics (~12 продьюсеров:
   `ArtifactPublishService`, `ExportRunRecoveryService`, миграторы, ledgers…),
   но «продолжать ли» там решает CAS-сага и `RetryPolicy`, а не
   `FailurePolicy`. Экстракционный конвейер не эмитит почти ничего.
5. **First-class данные обрываются на границе use case.** Даже если диагностика
   доезжает в финальный `Envelope`, `IocExtractionService` выбрасывает её при
   сборке `ExtractionResult`: driving-адаптер видит только счётчики и не может
   отличить чистый успех от завершения с пропусками.
6. **Текущий write-stage смешивает preparation и commit.** `JdbcIocSink`
   фильтрует, назначает ID, маппит строки и сразу пишет canonical storage.
   Возврат `Result<Integer>` после этого не дал бы настоящего policy-checkpoint:
   `fail-fast` узнал бы об element ERROR уже после durable side effect.

Проявление: один битый элемент данных стоит всего файла (dead-letter в daemon,
падение команды в oneshot); в логах stacktrace без machine-readable кода;
отчёта «что пропущено» не существует. При этом [0016] уже вывел операторские
ошибки конфигурации в boot-контур — рантайм-двойники этих проверок потеряли
смысл.

## Решение 1 — доктрина: два контура, три канала и impact

Два контура **узакониваются, а не унифицируются**:

| Контур | Кто решает «продолжать?» | Роль `Diagnostic` |
|---|---|---|
| **Envelope-контур** — extraction pipeline (oneshot + daemon ingest) | `FailurePolicy` над `Envelope.diagnostics()` на границах стадий (`PipelineRunner`) | **данные результата**: едут в конверте, участвуют в политике и входят в итоговый outcome |
| **Saga-контур** — export/publish/fetch (0011–0013) | CAS-машина состояний ledger'ов + `RetryPolicy` | **наблюдаемость операции**: эмиссия в sink рядом с durable-сменой состояния саги |

Внутри envelope-контура — три канала по классу сбоя:

1. **Element-level** (можно отбросить ровно один элемент, не нарушив инварианты
   оставшегося результата: битая строка, неподдержанный indicator, значение,
   не влезшее в маппинг) → **`Diagnostic` как данные + локальное продолжение
   стадии, включая severity ERROR**. Продьюсер не бросает исключение и не
   решает судьбу run. На следующей границе стадии `FailurePolicy` может
   остановить run; поэтому `fail-fast` означает **stop-after-stage**, а не
   немедленный выход из внутреннего цикла элемента.
2. **Run-level** (продолжение текущего source/run бессмысленно: source нельзя
   распарсить, формат целиком не поддержан, недоступна БД, сломан atomic move) →
   исключение остаётся средством управления потоком, но становится
   **наблюдаемым typed-сигналом**:
   - граница с точным кодом бросает `DiagnosticException(diagnostic)` — например,
     Tika-граница → `SOURCE.READ_FAILED`/`SOURCE.UNSUPPORTED_FORMAT`;
   - для голого исключения без кода `PipelineRunner` строит
     `PIPELINE.STAGE_FAILED` (`stage`, `cause`).
   Поведение daemon-пути (retry → dead-letter + sidecar) не меняется.
3. **Операторская ошибка конфигурации** → boot-контур [0016]
   (preflight/`CONFIG.*`), в рантайм не долетает. Рантайм-двойники удаляются.

Критерий выбора — не только «зависит ли сбой от runtime-данных», а
**минимальная единица, которую можно безопасно отбросить**. Другой файл с тем же
конфигом может быть валиден, но неподдержанный формат текущего файла всё равно
run-level: продолжать следующие стадии с отсутствующим текстом нельзя.

Эта классификация становится исполнимой metadata каталога. `DiagnosticCode`
получает неизменяемый `impact()`:

```java
public enum DiagnosticImpact {
    ELEMENT,    // влияет на один элемент envelope-потока
    RUN,        // относится ко всему текущему run/source
    OPERATION   // относится к операции со своей policy/state machine
}
```

Impact не выводится из severity/category и не переопределяется продьюсером.
Сам по себе RUN не означает останов: например, `SOURCE.EMPTY_TEXT` — RUN/WARN,
а `SOURCE.READ_FAILED` — RUN/ERROR с исключением. Управление потоком задают
канал доставки + severity + `FailurePolicy`, impact отвечает только на вопрос
«какую единицу обработки описывает факт».
Он публикуется в `DIAGNOSTICS-CATALOG.md`, позволяет фильтровать report/
quarantine consumers и не смешивает ERROR элемента с ERROR операции. Boot-коды
0016 живут в другом lifecycle и в этот enum-контур не входят.

## Решение 2 — эмиссия по границе стадии без дублей

`PipelineRunner` получает четыре явные зависимости: `FailurePolicy`,
`PipelineObserver`, `DiagnosticSink` и `DiagnosticFactory` (для generic
`PIPELINE.STAGE_FAILED`). `platform-etl` уже зависит от
`platform-diagnostics`; направление модульной зависимости не меняется.

Для каждой стадии исполняется один канонический протокол:

1. открыть observer/MDC scope и запомнить размер diagnostics на входе;
2. выполнить `stage.process(stageInput)`;
3. если стадия вернула envelope — эмитить только новый хвост diagnostics;
4. **после flush** оценить кумулятивную `FailurePolicy`;
5. только после успешной оценки вызвать `stageCompleted` и закрыть scope.

Exception-path разделён от policy-path:

- `DiagnosticException`, брошенный **самой стадией/портом**, эмитит вложенную
  диагностику один раз внутри stage-scope, отмечает stage failed и
  пробрасывается;
- другое исключение стадии превращается в `PIPELINE.STAGE_FAILED`, эта
  диагностика эмитится один раз, observer получает failure, наружу идёт
  `DiagnosticException` с построенной диагностикой и original cause;
- `DiagnosticException`, брошенный `FailurePolicy` **после flush**, повторно не
  эмитится: несомая диагностика уже была в дельте. Observer фиксирует отказ
  стадии, после чего исключение пробрасывается.

Так не теряются diagnostics предыдущих стадий и нет двойной бухгалтерии в
пределах одного pipeline run. Повторный daemon-attempt — новый run и законно
создаёт новые occurrences; меж-run дедупликация является задачей будущего
durable report/quarantine ledger, а не in-memory runner.
Финальный flush из `IocExtractionService` удаляется. Эмиссия остаётся внутри
открытого stage-scope, поэтому synchronous logging sink получает
`ioc.run.id`/`ioc.stage`; пин-тест на MDC обязателен.

`DiagnosticException` означает **stopping diagnostic**, а не обязательно
FATAL: `fail-fast` законно переносит ERROR. Javadoc класса актуализируется.

### Контракт отказа `DiagnosticSink`

Обычный `DiagnosticSink` — observational driven port и **не меняет outcome
обработки**. Production wiring оборачивает реализации в non-throwing
`ResilientDiagnosticSink`: failure одного delivery consumer изолируется,
попадает в отдельный low-level health/operational signal и не рекурсирует через
тот же diagnostic port. Decorator собирается во внешнем bridge/bootstrap слое:
`platform-diagnostics` не получает зависимости на SLF4J/health. Будущий
`CompositeDiagnosticSink` изолирует delegates друг от друга.

Если доставка становится частью корректности (например, обязательный durable
карантин), это уже не best-effort sink: ей нужен отдельный порт/ledger и явная
failure policy на orchestration boundary. Нельзя молча сделать pipeline
зависимым от доступности логгера или report writer.

При миграции typed producer прежний локальный error-log того же сбоя удаляется.
Operational lifecycle event (`stage_complete/failure`) и diagnostic event могут
сосуществовать, но третий stacktrace из Tika/mapper был бы дублем.

## Решение 3 — configurable continuation и явный completion outcome

Новый ключ `ioc.pipeline.failure-policy: fail-fast | collect-and-continue` —
typed enum-селектор по конвенции [0016] (unknown key/значение отбивает strict
preflight без дополнительного кода). Дефолт — **`fail-fast`**: поведение
существующих установок не меняется молча. Прод-шаблон daemon
(`packaging/templates/application.yml`) переводится на
`collect-and-continue` — целевой сценарий фида: битый элемент не должен
стоить файла.

Семантика `FailurePolicy`:

- fail-fast останавливает на первом ERROR/FATAL, увиденном **на stage
  boundary**;
- collect продолжает при ERROR и останавливается только на FATAL;
- пороги `max-errors`/per-code/per-impact — расширение той же Strategy, а не
  логика продьюсеров.

Continuation и качество завершения — разные решения. Финальный use-case
результат получает:

```java
public enum CompletionStatus {
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    COMPLETED_WITH_ERRORS
}
```

`ExtractionResult` возвращает immutable diagnostics (в пределах бюджета из
Решения 7), агрегированные counts и `CompletionStatus` вместе с текущими
счётчиками extracted/retained/written. FATAL результата не создаёт: run
завершается исключением.

Следствия по driving boundaries:

- oneshot CLI отображает summary; `COMPLETED_WITH_ERRORS` получает exit code
  `3`, не пересекающийся с picocli `SOFTWARE=1`/`USAGE=2`, даже если
  разрешённая policy довела commit до конца;
- daemon архивирует source как обработанный после успешного canonical commit,
  но `IngestSourceResult` сохраняет `ExtractionResult` с degraded completion;
- saga-status `COMPLETED` продолжает обозначать завершённость write→projection
  протокола, а не качество данных. Durable хранение diagnostic summary в
  `ingest_run` добавляется только вместе с report/quarantine consumer, чтобы не
  смешивать state machine и аналитику раньше времени.

## Решение 4 — две дороги и policy-checkpoint до commit

У диагностики два маршрута: **через данные** (возврат из порта → стадия →
конверт → policy) и **напрямую в sink** (минуя policy). Отсюда правило:

> **Envelope ELEMENT/RUN diagnostics severity ERROR/FATAL — только через
> данные. Advisory WARN и ниже допустимо напрямую в sink. OPERATION diagnostics
> saga-контура эмитятся напрямую независимо от severity, потому что решение
> принимает ledger/CAS/retry state machine.**

Advisory, который должен влиять на `CompletionStatus` или итоговый report
envelope-run, тоже обязан идти через данные независимо от severity. Прямой
advisory допустим для внешней операции, не являющейся частью extraction
outcome (например, post-commit CSV projection), либо как дополнительная
observational копия уже учтённого факта — но не как единственный маршрут
pipeline warning.

Одного изменения `IocSink.write()` на `Result<Integer>` недостаточно: результат
вернулся бы после durable write. Поэтому прежний смешанный `IocSink` разделяется
по ответственности:

```text
RetainedIndicators
        │
        ▼
PrepareArtifactsStage
  ArtifactPreparer.prepare(...) -> Result<ArtifactWritePlan>
  - filter / classify once / map per row
  - no DB/filesystem I/O
  - no advancement of persistent/shared ID sequence
        │  diagnostics appended to Envelope
        ▼
PipelineRunner flush + FailurePolicy checkpoint
        │  only accepted envelope continues
        ▼
WriteArtifactsStage
  - materialize/advance IDs
  - CanonicalArtifactRepository.write(...)
  - optional oneshot projection after successful commit
        │
        ▼
ArtifactWriteSummary
```

`ArtifactPreparer` — application out-port для config-driven подготовки;
реализация на базе `ConfigurableRowMapper` остаётся в adapter-sink-csv.
`ArtifactWritePlan` — immutable application value: artifact identity/header,
успешно подготовленные row candidates и данные, необходимые для commit, но без
внешних ресурсов и без захваченной транзакции. Финальные public IDs
материализуются после policy-checkpoint; rejected run не расходует shared
sequence и retry получает тот же baseline.

Pure `IdGenerator`/strategy при этом переносится из CSV-adapter в
`ioc-application/artifact`, где уже живёт artifact identity. Row candidate
помечает commit-time ID slot, а application sequence заполняет его только в
`WriteArtifactsStage`. Конкретная БД по-прежнему не знает CSV column DSL или
правила направления ID. Advancement и canonical write образуют один
application commit-step: при mapping rejection они не вызываются; поведение
sequence при storage exception пинится отдельно и не маскируется как element
failure.

Commit-time sequence выдаёт диапазон атомарно и не переиспользует его. Если
storage упал после reservation, допустим безопасный gap; повторная выдача того
же ID конкурентному/следующему run запрещена. Это дешевле и надёжнее
распределённой компенсации ID, а mapping/policy rejection до commit по-прежнему
не расходует диапазон вовсе.

`PrepareArtifactsStage` собирает результаты всех configured artifacts,
добавляет diagnostics одним batch и не выполняет side effects.
`WriteArtifactsStage` использует уже существующий
`CanonicalArtifactRepository`; для oneshot ему передаётся CSV projection, для
daemon — no-op, потому что daemon сохраняет write→projection saga в
`IngestionService`.

Per-row mapping failure должен быть **ожидаемым typed data outcome**, а не
`catch (RuntimeException)`: `SINK.ROW_MAPPING_FAILED` создаётся только для
явно классифицированной ошибки данных. Unknown provider/transform обязан быть
отбит preflight 0016; `NullPointerException`, нарушенный header invariant и
другие programming defects остаются run-level и превращаются в
`PIPELINE.STAGE_FAILED`. Это не позволяет collect mode скрывать баги как
«плохую строку».

Canonical batch-write остаётся run-level (`SINK.WRITE_FAILED` + exception),
atomic per artifact. Savepoint/retry на отдельной SQL-строке не вводится:
element defects отсекаются на preparation. Multi-artifact commit, как и сейчас,
не является общей транзакцией; daemon run-ledger/retry и keep-first canonical
write остаются recovery boundary.

`SINK.CHARSET_UNMAPPABLE` (OBS-2) — advisory WARN: immutable export writers
по-прежнему fail-fast на encoding error, а mutable `CsvArtifactProjection`
эмитит diagnostic напрямую, потому что замена символов не меняет решения о
продолжении canonical run.

## Решение 5 — миграция продьюсеров и единоличное владение фактом

| Категория | Судьба | Продьюсер / канал |
|---|---|---|
| CONFIG (2: `INVALID_PROPERTY`, `UNKNOWN_POLICY`) | **удалить** — superseded boot-контуром [0016] | — |
| SOURCE (3) | `READ_FAILED`, `UNSUPPORTED_FORMAT` — RUN через `DiagnosticException`; `EMPTY_TEXT` — RUN/WARN через envelope с пустым downstream payload | `TikaSourceReader` / `ReadSourceStage` |
| EXTRACTION (3) | `INDICATOR_SKIPPED` — ELEMENT DEBUG; `AMBIGUOUS_VALUE` — ELEMENT WARN; `PATTERN_INVALID` — удалить, если startup compilation полностью покрыта preflight | `ExtractIndicatorsStage` |
| CLASSIFY (2) | ELEMENT; `UNSUPPORTED_INDICATOR_TYPE` сохраняет ERROR и исключает только indicator | единый classification outcome до artifact mapping |
| PIPELINE | `STAGE_FAILED` — RUN из runner; `ITEM_SKIPPED` — ELEMENT DEBUG на dedup/filter drop; добавить suppression-summary из Решения 7 | `PipelineRunner`, application stages |
| SINK | `ROW_MAPPING_FAILED` — ELEMENT/Result из preparation; `WRITE_FAILED` — RUN/exception из commit | artifact preparer / `WriteArtifactsStage` |
| INGEST (3) | OPERATION/RUN: эмиссия + прежний throw; аналог policy — file ledger + retry + dead-letter | ingest adapter/application boundary |
| SYNC | OPERATION: mapping `RemoteErrorKind` → code только на финальном failure после `Retrier`; boot-superseded codes удалить | `RemoteFetchService`, `ArtifactPublishService` |

Один факт имеет одного владельца. В частности, classification вычисляется один
раз на indicator и материализуется в `ClassificationDecision`; `match.url`,
`match.host`, artifact filters, diagnostics и TRACE читают один outcome, а не
повторно вызывают `MatchPolicy`. Это одновременно не даёт задублировать
CLASSIFY diagnostics и уменьшает CODE-3.

Saga diagnostic эмитится после успешной durable фиксации соответствующего
failed/abandoned state либо явно описывает невозможность этой фиксации. Event
не заменяет ledger: control event допустим только после commit и только как
claim-check hint по доктрине [0013].

Дисциплина ratchet'а: allowlist `CatalogReferenceRatchetTest` сгорает до нуля;
каждый implementation slice уменьшает его. `DIAGNOSTICS-CATALOG.md`
регенерируется (`-Dioc.docs.update=true`) при каждом изменении каталога и
получает колонку Impact.

## Решение 6 — чистые domain outcomes и per-item TRACE

Полный TRACE нельзя достоверно построить из текущих API (`String`,
`List<RawIndicator>`, `MaskMatch`): application не знает сработавшее правило,
span end, признаки или проверенные predicates. Повторное вычисление ради лога
запрещено. Поэтому доменные операции возвращают **чистые данные решения**, не
логируют и не зависят от SLF4J/platform-observability:

- refang → `RefangOutcome(text, changes)`; change несёт rule identity и
  before/after span/value metadata;
- extraction → raw matches с pattern/type и полным span; overlap/drop decision
  выражается данными, а не повторным поиском application-слоя;
- classification → `ClassificationDecision(features, matchedRule, maskMatch,
  predicateResults)`; этот же объект используют providers/filtering;
- attribution → outcome с выбранным marker/source либо причиной отсутствия;
- dedup и artifact routing уже принадлежат application stages и формируют свои
  компактные decision records там.

Outcome — функциональный результат, а не «логовый DTO»: он устраняет повторные
вычисления и полезен diagnostics/tests независимо от включённого TRACE. Не
строятся параллельные heavyweight копии payload. После измерения допустимо
добавить detail mode, если compact decisions покажут заметную аллокационную
стоимость; до измерения второй API `withTrace()` не вводится.

Application stages эмитят решения только при двойном затворе:

```text
logger TRACE enabled
AND ioc.observability.per-item-trace-enabled = true
```

В logging taxonomy добавляется один стабильный action
`pipeline_item_decision` и минимальные project fields для decision kind,
indicator type/item identity, rule/pattern и outcome. Значения IOC допускаются
только на TRACE при явном флаге; query/token-like значения проходят redaction,
а по умолчанию используется short hash + type/span. Тесты пинят, что при любом
закрытом затворе не выполняются render/serialization и log emission.

TRACE остаётся operational observability, не `Diagnostic`: он не участвует в
`FailurePolicy`, catalog ratchet или completion status.

## Решение 7 — контекст, bounded accumulation и delivery identity

Element diagnostic несёт достаточную идентичность для расследования: type,
artifact, source и безопасный item identity. «Полная идентичность» не означает
безусловный raw IOC в WARN/ERROR. Контекст разделяется на:

- **policy/report context** внутри `Diagnostic` — точные структурные данные,
  доступные доверенному in-process consumer;
- **rendered/log context** — представление после redaction policy. Raw IOC
  остаётся DEBUG/TRACE-only по [dev/LOGGING.md](../dev/LOGGING.md), URL query и
  токены маскируются либо заменяются short hash.

Эмиссия остаётся per-diagnostic, но накопление bounded. Новый
`ioc.pipeline.max-diagnostics-per-run` имеет default **10 000** и принимает
только положительное значение.
Stage-local `BoundedNotification`:

1. сохраняет первые N occurrences и агрегирует counts по code/severity/impact;
2. никогда не теряет первый ERROR/FATAL, нужный policy;
3. при переполнении добавляет одну synthetic
   `PIPELINE.DIAGNOSTICS_SUPPRESSED` с total/counts/maxSeverity;
4. передаёт policy effective max severity, поэтому cap не превращает fail-fast
   в continue;
5. добавляется в envelope одним batch — многократный `withDiagnostic()` в
   per-item loop запрещён, чтобы не получить квадратичное копирование списка.

`ExtractionResult` summary считает и сохранённые, и suppressed occurrences.
TRACE имеет отдельный volume gate и в diagnostic budget не входит.

MDC достаточно текущему synchronous logging sink, но недостаточно будущей
durable/async доставке. При активации report/quarantine вводится delivery
wrapper `DiagnosticOccurrence` (`diagnostic` + run/source/stage + monotonic
sequence/stable occurrence id). Это явная эволюция sink boundary; нельзя
извлекать durable identity из thread-local MDC или нестабильного `Map.hashCode`.

## Решение 8 — карантин и дополнительные consumers (отложено)

Impact, безопасная element identity, per-diagnostic emission и будущий
`DiagnosticOccurrence` держат дверь открытой для **Invalid Message Channel**.
Файловый `failed/` daemon'а остаётся Dead Letter Channel для целого source.

Будущая durable сборка:

```text
ELEMENT ERROR occurrence
        -> QuarantineStore (idempotent durable write; truth)
        -> optional QuarantinedItemsChanged claim-check event
        -> report/UI/reprocess consumer
```

Если карантин optional, он может жить delegate-ом resilient composite sink.
Если оператор полагается на него как на гарантию отсутствия тихой потери, write
в `QuarantineStore` участвует в orchestration outcome и его failure становится
RUN diagnostic; best-effort `DiagnosticSink` такой гарантии не даёт.

Триггер активации — реальная операторская потребность ручного разбора. До него
не создаются ledger/schema/event. В KNOWN-ISSUES при закрытии D1 фиксируется
seam. Аналогично отложены:

- `ReportDiagnosticSink`/JSONL и durable run summary;
- metrics consumer только с low-cardinality labels code/impact/severity;
- политики `max-errors`, per-code/per-impact thresholds;
- i18n renderer;
- OpenTelemetry bridge. Диагностики не становятся control events и не требуют
  broker/event sourcing/CQRS.

## Отклонённые альтернативы

- **Observer-bridge для доставки diagnostic**
  (`PipelineObserver.stageDiagnostics(...)`). Это вторая дорога к тому же
  порту, смешение lifecycle observability и data outcome, риск двойной эмиссии.
  Реальный metrics/report consumer подключается через diagnostic sink/delivery
  boundary, не через pipeline observer.
- **Однофазный `IocSink.write() -> Result<Integer>`.** Result приходит после
  durable write, поэтому fail-fast не предотвращает side effect и daemon может
  назвать run failed после частичного commit. Preparation и commit разделены.
- **Railway-Oriented payloads** (`List<Ok|Failed>` через все стадии).
  Теоретически чище, но переписывает каждый payload; append-only envelope +
  domain outcomes дают нужную семантику меньшим изменением.
- **Element-level recovery внутри SQL batch** (savepoint на строку). Цена и
  связность несоразмерны: data defects отфильтровываются до commit, batch
  остаётся atomic per artifact.
- **Broad `catch (RuntimeException)` как row mapping failure.** Скрывает
  programming/config defects под collect mode; разрешён только typed
  element-outcome.
- **Numeric `event.severity` (ECS).** Typed fields — территория OBS-D3.
- **Шина/брокер/durable diagnostic queue.** Sink/ledger seams достаточны;
  анти-брокер доктрина 0013 сохраняется.
- **Смягчение FATAL.** FATAL обозначает недостоверность run; collect policy
  останавливается на нём.

## План реализации (срезы)

Один срез = один самостоятельный commit. Порядок ниже зависимый: следующий
срез вправе использовать только контракты, уже опубликованные предыдущими.
Временные cross-module API, заведомо удаляемые в следующем commit, не вводятся.

### 1. Catalog impact как исполнимый контракт

**Commit:** `FEATURE: OBS-D1 classify diagnostic impact`

- добавить `DiagnosticImpact` и `DiagnosticCode.impact()`;
- классифицировать все остающиеся catalog constants как ELEMENT/RUN/OPERATION;
- расширить `DiagnosticCatalogEntry`, generator и `DIAGNOSTICS-CATALOG.md`
  колонкой Impact;
- дополнить catalog structural tests: impact non-null, generated doc sync;
- пока не удалять superseded codes — их судьба решается вместе с producer
  migration, чтобы первый commit был только additive contract change.

**Модули:** `platform-diagnostics`, `docs/`.

**Gate:** `./mvnw -pl platform/platform-diagnostics -am test` + doc-update test.

### 2. Exactly-once delivery protocol в runner

**Commit:** `FEATURE: OBS-D1 emit pipeline diagnostics exactly once`

- добавить `DiagnosticSink` + `DiagnosticFactory` в `PipelineRunner` и разделить
  stage-exception, delta-flush и policy-rejection paths;
- generic exception стадии переводить в RUN `PIPELINE.STAGE_FAILED`, typed
  `DiagnosticException` unwrap-ить без второго generic code;
- policy rejection после flush не эмитить повторно;
- удалить финальный flush из `IocExtractionService`;
- добавить non-throwing resilient decorator во внешнем diagnostics/logging
  bridge и собрать его в bootstrap, не добавляя SLF4J в diagnostics/etl;
- актуализировать `DiagnosticException` как carrier stopping diagnostic.

Локальные producer error-logs пока не удаляются: до producer migration они
остаются единственным точным сигналом своих адаптеров.

**Модули:** `platform-etl`, `platform-diagnostics`,
`platform-diagnostics-logging`, `ioc-application`, `bootstrap`.

**Gate:** runner tests на success/typed throw/generic throw/policy rejection,
MDC pin, throwing delegate isolation; затем
`./mvnw -pl platform/platform-etl,platform/platform-diagnostics-logging,core/ioc-application -am test`.

### 3. Configurable policy, budget и completion outcome

**Commit:** `FEATURE: OBS-D1 expose diagnostic completion outcome`

- добавить typed `PipelineFailurePolicy` и ключи
  `ioc.pipeline.failure-policy`, `max-diagnostics-per-run` (default 10 000) во
  все config surfaces: model, defaults, strict schema tests, packaging template;
- ввести `BoundedNotification`/summary: cap, first rejecting diagnostic,
  suppression counts и synthetic `PIPELINE.DIAGNOSTICS_SUPPRESSED`;
- добавить `CompletionStatus` и immutable diagnostics/summary в
  `ExtractionResult`, протащить через `IngestSourceResult`;
- собрать `FailurePolicy` в composition root: application default fail-fast,
  production daemon template collect-and-continue;
- pin driving semantics: oneshot `COMPLETED_WITH_ERRORS` → exit code `3`;
  daemon сохраняет structurally completed saga и degraded extraction result.

**Модули:** `platform-diagnostics`, `ioc-application`, `adapter-cli-picocli`,
`bootstrap`, `packaging`.

**Gate:** config binding/preflight, обе policy, suppression не скрывает
ERROR/FATAL, CLI exit, daemon result; targeted reactor tests до bootstrap.

### 4. Pure outcomes refang/extraction/attribution

**Commit:** `REFACTOR: expose domain processing decisions`

- заменить scalar/list-only ответы refang, extraction и attribution на compact
  immutable outcomes, сохраняющие прежний functional payload плюс факты
  решения (rule/pattern/span/marker/drop reason);
- мигрировать application stages и adapter-regex consumers атомарно;
- не добавлять logging/diagnostics imports в domain;
- не эмитить TRACE в этом срезе: outcomes сначала становятся обычным
  проверенным API, независимо от observability consumer;
- сохранить golden extraction output без изменений.

**Модули:** `ioc-domain`, `ioc-application`, `adapter-regex-re2j`, `bootstrap`.

**Gate:** domain tests на полноту decision facts, application stage tests,
golden pipeline test, ArchUnit core boundaries.

### 5. Единственный classification outcome на indicator

**Commit:** `REFACTOR: materialize classification decisions once`

- ввести `ClassificationDecision` с features, matched rule/predicates и
  `MaskMatch`; `RuleBasedMatchPolicy` возвращает его без повторного extraction;
- материализовать decision один раз на indicator на application boundary после
  attribution и до artifact preparation;
- перевести `match.url`, `match.host`, address providers и artifact filters на
  один decision вместо повторных `MatchPolicy`/`FeatureExtractor` вызовов;
- сохранить declarative first-match-wins и все четыре match variants;
- добавить counting tests: features/classification вычисляются один раз на
  indicator независимо от числа columns/providers.

**Модули:** `ioc-domain`, `ioc-application`, `adapter-sink-csv`, `bootstrap`.

**Gate:** classify/domain tests, output-mapping tests, golden pipeline и
performance-regression counting test (не timing assertion).

### 6. Artifact preparation → policy checkpoint → commit

**Commit:** `FEATURE: OBS-D1 reject invalid rows before canonical commit`

- заменить смешанный `IocSink` на `ArtifactPreparer` + immutable
  `ArtifactWritePlan` и отдельный commit path через
  `CanonicalArtifactRepository`;
- добавить `PrepareArtifactsStage` перед `WriteArtifactsStage`;
- перенести pure ID sequence/strategy в `ioc-application/artifact`, финальный ID
  slot материализовать только после policy checkpoint;
- row mapping data failure возвращать как ELEMENT
  `SINK.ROW_MAPPING_FAILED`; не ловить произвольный `RuntimeException`;
- storage/batch failure переводить в RUN `SINK.WRITE_FAILED`;
- сохранить oneshot projection после commit и daemon write→projection saga.

**Модули:** `ioc-application`, `adapter-sink-csv`, `adapter-store-jdbc`,
`bootstrap`.

**Gate:** 4999/1 — fail-fast даёт 0 durable writes/0 ID advancement, collect
пишет 4999 и возвращает `COMPLETED_WITH_ERRORS`; SQL failure остаётся RUN;
зарезервированный диапазон после storage failure не переиспользуется;
repository/TCK, oneshot и daemon recovery tests.

### 7. Envelope producer migration

**Commit:** `FEATURE: OBS-D1 produce typed pipeline diagnostics`

- подключить SOURCE/EXTRACTION/CLASSIFY/PIPELINE producers к outcomes прошлых
  срезов и складывать diagnostics stage-local batch'ами;
- `READ_FAILED`/`UNSUPPORTED_FORMAT` бросать как typed RUN diagnostics,
  `EMPTY_TEXT` возвращать RUN/WARN через envelope;
- классификационные/data drops выражать ELEMENT diagnostics с safe identity;
- удалить дублирующие Tika/mapper error logs после появления канонического
  runner delivery;
- применить redaction policy к WARN/ERROR renderer;
- уменьшить ratchet allowlist только на фактически подключённые constants.

**Модули:** `ioc-application`, `adapter-source-tika`, `adapter-sink-csv`,
`platform-diagnostics-logging`, `bootstrap`.

**Gate:** producer contract tests, raw IOC отсутствует в WARN/ERROR capture,
stage failure сохраняет exact code/cause/MDC, обе policy на mixed batch.

### 8. Structured per-item TRACE

**Commit:** `FEATURE: OBS-D1 trace per-item pipeline decisions`

- добавить `pipeline_item_decision` и минимальные `LogField` constants,
  регенерировать `LOGGING-CATALOG.md`;
- сделать `per-item-trace-enabled` реальным consumer-flag в application wiring;
- эмитить refang/extract/classify/attribute/dedup/route decisions из уже
  рассчитанных outcomes, без повторных domain вызовов;
- двойной gate проверять до render/serialization; raw/query values применять
  только по TRACE redaction contract;
- выполнить allocation/runtime smoke на golden source и synthetic large input,
  но не вводить нестабильный timing gate в unit tests.

**Модули:** `platform-observability`, `ioc-application`, `bootstrap`, `docs/`
(generated logging catalog).

**Gate:** taxonomy/doc-sync, оба gate-off сценария = 0 emissions и 0 renderer
calls, gate-on structured capture, counting test без повторной classification.

### 9. Ingest/sync producers и финальная чистка catalog

**Commit:** `FEATURE: OBS-D1 complete diagnostic producer coverage`

- подключить INGEST diagnostics к file-ledger/retry/dead-letter boundaries;
- маппить `RemoteErrorKind` в SYNC code на final failure после `Retrier` и
  durable state transition; listing/detection failures покрыть тем же
  transport-neutral правилом;
- удалить `ConfigDiagnosticCodes`, `PATTERN_INVALID` и SYNC superseded constants,
  если live producer audit подтверждает boot/state-machine замену;
- ratchet allowlist довести до ∅, regenerated `DIAGNOSTICS-CATALOG.md` должен
  совпасть с фактическим набором producers;
- pin: diagnostic не подменяет ledger transition и control event не несёт
  diagnostic payload.

**Модули:** `ioc-application`, `adapter-ingest`, `adapter-transport-smb`,
`adapter-store-jdbc`, `bootstrap`, `platform-diagnostics`, `docs/` generated.

**Gate:** retry/final-failure mapping, ingest recovery/dead-letter, sync saga,
ratchet empty, architecture tests.

### 10. Published docs, registry и итоговый review

**Commit:** `DOCS: close OBS-D1 and publish diagnostic outcome contract`

- обновить dev/DIAGNOSTICS.md, dev/pipeline.md, dev/LOGGING.md, affected module
  READMEs, config/packaging references и `KNOWN-ISSUES.md`;
- OBS-D1 закрыть ссылками на implementation commits; quarantine/report/
  occurrence seams зарегистрировать отдельно, не выдавая их за реализованные;
- проверить generated catalogs через update-mode без ручного редактирования;
- провести holistic review dependency direction, OOP/SOLID, exception ownership,
  sink failure isolation, concurrency и allocation hot paths;
- финальный `./mvnw verify`, link-check и `git diff --check`.

**Модули:** `docs/`, READMEs; production code в этом срезе не меняется, кроме
найденного review-blocker, который оформляется отдельным fix commit, а не
прячется в DOCS.

Промежуточный ratchet allowlist может только уменьшаться. Перед каждым commit
проходят targeted tests затронутого reactor-среза; после срезов 3, 6, 9 и 10 —
полный `./mvnw verify`, потому что это внешние behavioral checkpoints
(policy/outcome, durable write, producer coverage, delivery).

## Обязательные инварианты и тесты

- одна diagnostic occurrence эмитится не более одного раза в пределах run;
- exception diagnostic стадии эмитится до закрытия MDC scope;
- policy rejection не re-emit'ит уже flush-нутый diagnostic;
- failure observational sink не меняет processing outcome и не вызывает
  рекурсию;
- fail-fast mapping ERROR не выполняет durable write и не продвигает ID;
- collect mapping ERROR пишет только валидные rows и возвращает degraded
  completion;
- RUN failure никогда не маскируется как element outcome;
- cap не скрывает ERROR/FATAL от policy и даёт точный suppression summary;
- raw IOC отсутствует в WARN/ERROR rendered logs;
- TRACE не выполняет render/emission при закрытом хотя бы одном затворе;
- classification/features вычисляются один раз на indicator;
- saga diagnostic не подменяет ledger transition и не является control event.

## Следствия

- Поведение default application config остаётся fail-fast, но теперь policy
  действительно проверяется **до** canonical commit element-defect batch.
- Production daemon явно использует collect: валидные элементы сохраняются,
  пропуски возвращаются как `COMPLETED_WITH_ERRORS`, а не маскируются под чистый
  успех.
- Diagnostics не теряются при stage failure и не дублируются между producer,
  runner и policy.
- `platform-diagnostics` расширяется impact/bounded outcome semantics;
  `platform-etl` — runner protocol; `ioc-domain` — чистые decision outcomes;
  `ioc-application` — completion + две pipeline стадии/порты; adapters —
  preparer/producers; bootstrap — typed config/wiring.
- Dependency direction сохраняется: domain outcomes — pure Java;
  application зависит от domain/platform ports; mapping/storage/logging остаются
  во внешних modules; Spring знает policy/config только в composition root.
- `LoggingDiagnosticSink` сохраняет severity→level mapping OBS-1; redaction и
  resilient delivery окружают его, не переносят policy в bridge.
- OBS-D3 (typed ECS fields) остаётся вне scope.

## Ссылки

[0003](0003-diagnostics.md) — механизм диагностики ·
[0007](0007-logging-observability.md) — ECS/MDC-наблюдаемость ·
[0008](0008-stage-6-8-implementation-followups.md) — исходный план интеграции ·
[0013](0013-event-driven-coordination.md) — anti-broker, claim-check, sagas ·
[0016](0016-config-preflight-strict-binding.md) — boot-контур операторских ошибок ·
[dev/DIAGNOSTICS.md](../dev/DIAGNOSTICS.md) · [dev/LOGGING.md](../dev/LOGGING.md) ·
[dev/LOGGING-TAXONOMY.md](../dev/LOGGING-TAXONOMY.md) ·
[LOGGING-CATALOG.md](../LOGGING-CATALOG.md), [DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md) ·
шаг 1 блока OBS: `752d186..aca604e` (ratchet-baseline: `2681c4d`).

## Дополнение 2026-07-13 — итог реализации

Это датированное дополнение заменяет только implementation-status в
начале ADR: срезы 1–9 и итоговый review завершены. Решения 1–8
не меняются; ниже фиксируется точная форма их исполнения.

Implementation chain:

| Срез | Коммит(ы) |
|---|---|
| 1 | `354c5c6` |
| 2 | `28b2f53` |
| 3 | `49ecfd9`, `3d2d1d0`; follow-up bounded/UX fix `53077c9` |
| 4 | `ad7b08b` |
| 5 | `4038173`; ordering/performance fix `5b44f11` |
| 6 | `2c18bbd` |
| 7 | `0dee6cd` |
| 8 | `ae7f268` |
| 9 | `32b4202` |
| Итоговый review | terminal-delivery fix `d34b733`, cleanup `c990b98` |

Уточнения по live code:

1. `RefangDecision` хранит applied rule и число replacements, а не
   before/after metadata каждой замены. Это сохраняет полную rule-level
   объяснимость TRACE и ограничивает аллокации числом rules, а не
   числом replacements. Extraction по-прежнему несёт exact pattern/span/status.
2. Batch-local dedup передвинут до classification: decision — чистая
   функция `type|value`, поэтому retained outcome не меняется, а дубликаты
   не оплачивают feature extraction. `MatchPolicy` вызывается только для
   NETWORK; FILE получает neutral decision.
3. `CLASSIFY.AMBIGUOUS_MATCH` удалён. Декларативная policy намеренно
   first-match-wins, rules могут пересекаться, и домен не считает это defect.
   Эмитить такой код без нового domain invariant означало бы ложный WARN.
4. `INGEST.DEAD_LETTER_FAILED` добавлен к трём запланированным INGEST
   constants: physical failed-area transition — отдельная operation boundary,
   которую
   `LEDGER_WRITE_FAILED` описать не может.
5. Routing decision формирует `adapter-sink-csv`, потому что именно там
   известны configured artifact filter и typed mapping result. Адаптер передаёт
   compact application-owned `PipelineItemDecision` через порт; logging импорт в
   application/adaptor mapping не появился.
6. Final review запинил ещё два edge cases: durable `FAILED` на повторе
   ingest не превращается в ложный success и не теряет исходную typed
   diagnostic; suppression summary эмитится не только при normal completion,
   но и перед policy rejection.

Не реализованы и остаются явными seams: durable `DiagnosticOccurrence`,
report/JSONL, element quarantine/reprocess, threshold policies, i18n, metrics и
OpenTelemetry consumers. Они не требуют брокера и не меняют anti-broker/
ledger-first доктрину.
