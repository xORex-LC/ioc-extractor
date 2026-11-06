# Диагностика и операционная наблюдаемость

[English version](../observability.md)

Диагностика описывает результат обработки и может влиять на completion/failure
policy. Операционные логи описывают выполнение, I/O, lifecycle, повторы и
координацию. Исключения переносят сбои между границами и не заменяют эти модели.
Документ объясняет их композицию в IOC. Контракты reusable API и самостоятельные
примеры принадлежат связанным библиотечным руководствам.

## Поток выполнения

```text
stage outcome -> DiagnosticBatch (retained detail + exact counts)
  -> run accumulation + delivery of newly retained occurrences
  -> failure-policy checkpoint -> continuation or rejection
  -> ExtractionResult + DiagnosticSummary when processing completes

stage exception -> owning diagnostic boundary -> diagnostic delivery + failure

runtime action -> typed LogEvent / MDC scope -> SLF4J -> appender / encoder
Diagnostic -> LoggingDiagnosticSink -> ordinary typed operational record
```

Runner обрабатывает append-only delta стадии, не передавая заново весь прежний
sample. Diagnostic occurrence принадлежит либо outcome, либо `DiagnosticException`,
не обоим каналам. Логическая доставка на этой границе не является durable log
delivery и не обещает сохранение/вывод каждой наблюдавшейся occurrence.

## Границы владения

| Владелец | Ответственность |
|---|---|
| `platform-diagnostics-api` | Framework-free values, result/policy, ограниченное накопление и синхронные sinks |
| `platform-diagnostics` | IOC codes, metadata, exception и renderer |
| `platform-logging-slf4j` | Общие typed scalar events, normalization, restoring MDC и явные masks |
| `platform-observability` | IOC fields/actions, facades и ETL logging observer |
| `platform-diagnostics-logging` | Diagnostic-to-log bridge и редактирование IOC context |
| `bootstrap/ioc-app` | Композиция, decision TRACE, health/metrics, encoder и назначения логов |

Domain возвращает pure decisions без зависимости от этих модулей; application
создаёт diagnostics и decision observations. Общие библиотеки не выбирают IOC
catalog, failure checkpoint, provider и service recovery policy.
[ADR 0044](../../ADR/0044-reusable-diagnostics-contract.md) и
[ADR 0045](../../ADR/0045-reusable-slf4j-logging-contract.md) фиксируют границу.
Локальная структура принадлежит справочникам [diagnostics](../../../platform/platform-diagnostics-api/README.md)
и [logging](../../../platform/platform-logging-slf4j/README.md), consumer contracts —
[руководству diagnostics](../../guides/ru/libraries/diagnostics.md) и
[руководству logging](../../guides/ru/libraries/logging-slf4j.md).

## Идентичность и представление диагностики

`DiagnosticCode` задаёт stable ID, default severity и независимый impact.
IOC `IocDiagnosticCode` добавляет category, message key и default template.
Общий код отображается буквальным ID без IOC category; bridge скрывает и ambient
category. Текущий перечень генерируется в
[DIAGNOSTICS-CATALOG.md](../../DIAGNOSTICS-CATALOG.md).

Producer передаёт code и structured context. `DiagnosticRenderer` владеет
представлением сообщения, не меняя diagnostic или Throwable. Code equality и
metadata должны оставаться стабильными. Occurrences считаются независимо:
равенство диагностик не превращает их в одно наблюдение.

Impact (`ELEMENT`, `RUN`, `OPERATION`) принадлежит code metadata, не severity.
Он определяет расход бюджета, не разрешение пропустить строку, commit или retry.
Saga diagnostics сообщают final fact после retries/state transition; durable
ledger остаётся authority восстановления.

## Бюджеты, политика и завершение

Stage collectors сохраняют ограниченный sample и rejecting signals; run хранит
не больше своего ELEMENT/RUN бюджета, при необходимости заменяя слабые samples.
Оба сохраняют все OPERATION occurrences вне бюджета. Точные counts переживают
compaction, но память, размер контекста и общее число доставок глобально не
ограничены этим лимитом. Точные алгоритмы описаны в библиотечном руководстве.

При run budget 2 вход WARN A, ERROR B, FATAL C оставляет `[C, B]`: сохраняется
позиция sample, не encounter order. Built-in policy может выбрать C вместо
самой ранней rejecting B. Правило, зависящее от каждого code, должно видеть
наблюдения до compaction. Согласуйте stage/run budgets при сборке pipeline.

`PIPELINE.DIAGNOSTICS_SUPPRESSED` — synthetic display marker. Объединяйте реальные
batches и summaries, не маркер как новую occurrence. Runner передаёт stage deltas
через retained-delivery callback и выводит suppression summary на соответствующей
terminal boundary. Подготовка может отложить final marker до continuation или
cancellation. Чтение snapshot само ничего не доставляет.

| Наблюдавшаяся severity | `fail-fast` | `collect-and-continue` |
|---|---|---|
| TRACE / DEBUG / INFO / WARN | Продолжить | Продолжить |
| ERROR | Остановить | Продолжить |
| FATAL | Остановить | Остановить |

Оценка policy сама не выбрасывает product exception и не отменяет работу;
решение исполняет orchestrator до соответствующих durable effects. Остановленный
run не обязан возвращать completed result. У возвращённого результата
`CompletionStatus` использует полный `DiagnosticSummary`, включая suppressed
counts: ERROR/FATAL дают `COMPLETED_WITH_ERRORS`, иначе WARN —
`COMPLETED_WITH_WARNINGS`, иначе — `COMPLETED`. Extraction CLI возвращает `3`
для завершения с ошибками; отказ команды — отдельный исход.

Сбор ошибок поэтому не означает успеха, а меньшее число log records не меняет
policy/completion. Документ фиксируется по артефактам; поздний сбой artifact или
projection не откатывает прежние записи. Checkpoint и partial-effect boundaries
описаны в [processing](processing.md).

## Сбои доставки и наблюдателей

Default mapping severity в log level стабилен:

| Diagnostic severity | Log level |
|---|---|
| `FATAL`, `ERROR` | `ERROR` |
| `WARN` | `WARN` |
| `INFO` | `INFO` |
| `DEBUG` | `DEBUG` |
| `TRACE` | `TRACE` |

Bootstrap оборачивает `LoggingDiagnosticSink` в `ResilientDiagnosticSink`. Logging
вызывается один раз, runtime failure сообщается fallback callback; durable queue,
timeout и automatic retry нет. Runtime failure fallback поглощается; `Error`
выходит наружу. Отсутствие log record не означает успех обработки и не доказывает
откат уже committed операции.

Raw batch delivery callback может упасть после частичной мутации; повтор всего
batch может повторно посчитать/доставить принятый префикс. Эта обёртка не делает
все переданные pipeline observers/scopes и bare log builders устойчивыми.
Provider/cleanup failure может выйти через такие call sites. У decision TRACE
и managed-import observation собственное containment для RuntimeException;
это не обещает восстановления при произвольном fatal `Error`.

Durable diagnostic occurrences/report/quarantine остаются seam OBS-4 в
[KNOWN-ISSUES.md](../../KNOWN-ISSUES.md). File appender или collecting sink не
являются этой report model и не владеют бизнес-транзакцией.

## Типизированные логи и correlation

IOC `LogField`, `EventAction`, `EventOutcome`, `LogEvent` связывают локальные
каталоги с reusable API. `event.action` — stable machine-readable operation,
`message` — человеческий текст. Generated [LOGGING-CATALOG.md](../../LOGGING-CATALOG.md)
владеет перечнем fields/actions. Project fields остаются в `ioc.*`, ECS fields
сохраняют значения. Run ID — не APM `trace.id` или `transaction.id`.

Driving boundary создаёт run identity; daemon ingest использует identity durable
`ingest_run`. Pipeline run/stage scopes восстанавливают ambient MDC; terminal
диагностика остаётся в run без ложной последней stage. MDC несёт STRING context,
event-local STRING/LONG/BOOLEAN передаются typed SLF4J pairs. Поля проверяются
даже при выключенном logging level.

Ключ определяет один тип в event, в том числе при null declaration. При enabled
emission ненулевое local value временно скрывает точный same-key MDC string и
восстанавливает его после вызова. Null убирает local value, не скрывая ambient
MDC; для подавления этой копии нужен явный hide scope. Parent/child path conflicts
и reserved metadata — ответственность schema сервиса. Global field registry нет.

MDC scopes принадлежат одному потоку и закрываются LIFO. Создавайте/наполняйте
worker scope внутри самого worker по явным command metadata; автоматического
переноса контекста нет. Сначала получите scope, затем наполняйте, чтобы cleanup
был доступен при сбое. Ошибка provider может помешать полному восстановлению.

Один action может описывать несколько lifecycle records: start содержит
`event.outcome=unknown`, terminal success/failure — `event.duration`.
Например, `ingest_recover` не заменяет диагностику
`INGEST.RECOVERY_FAILED` или `INGEST.STATE_TRANSITION_CONFLICT`.
Control-event publish/dispatch и keyed admission остаются operational signals,
не требуя искусственной processing category `EVENTS`.

## Режимы и назначения логирования

Поставляемая конфигурация связывает `ioc.observability.mode` с active Spring
profiles; `ioc.runtime.mode` отдельно выбирает application lifecycle. Installed
daemon config задаёт нужное сочетание. Одного runtime mode недостаточно, чтобы
доказать загрузку конкретного appender/profile.

Logging profile `daemon` пишет nested ECS JSON rolling appender в
`logging.file.path`, а также console. Другие profiles используют console path;
форматы console/file не обязаны совпадать. Rotation/retention принадлежат
[logback-spring.xml](../../../bootstrap/ioc-app/src/main/resources/logback-spring.xml),
а не второму перечню правил здесь. Проверяйте destination bytes и encoder status,
не только успешный logger call.

## ECS wire representation

Logical dotted fields материализуются в daemon file как nested JSON objects:

```json
{
  "ecs": {"version": "8.11"},
  "event": {
    "action": "app_start",
    "outcome": "success",
    "dataset": "ioc-extractor"
  }
}
```

Прежнее flat representation использовало такие пути:

```json
{
  "ecs.version": "8.11",
  "event.action": "app_start",
  "event.outcome": "success"
}
```

| Logical field | Старый flat query | Текущий nested query |
|---|---|---|
| `ecs.version` | `jq '.["ecs.version"]'` | `jq '.ecs.version'` |
| `service.name` | `jq '.["service.name"]'` | `jq '.service.name'` |
| `event.action` | `jq '.["event.action"]'` | `jq '.event.action'` |
| `event.outcome` | `jq '.["event.outcome"]'` | `jq '.event.outcome'` |
| `ioc.run.id` | `jq '.["ioc.run.id"]'` | `jq '.ioc.run.id'` |

`event.outcome` не новое поле: изменился physical path. Typed migration меняет
scalar types без переименования logical keys. Queries, collectors и dashboards
должны учитывать совместимость формы и типа.

`IocEcsStructuredLogEncoder` добавляет configured `event.dataset` как key/value
pair до nested rendering Boot и затем восстанавливает исходный event. Так
устраняется конкурирующий static JSON-add member. В отличие от строгого
[Boot recipe](../../guides/ru/libraries/logging-slf4j-boot.md), IOC encoder передаёт
non-mutable events и события с готовым dataset pair стандартному encoder; он
не устанавливает все rejection guards рецепта. Важны catalog ownership и
поддержанные producers. Raw SLF4J/MDC всё ещё могут создавать конфликты.
При Throwable отдавайте Boot владение `error.*`; type-only record может задать
`error.type`. Конфликтующий input может привести к потере записи и appender error
без исключения у producer. Service contract проверяет `LogbackConfigurationIT`;
у consumer recipe своя область квалификации.

## Миграция запросов

Control-event type из `LoggingControlEventObserver` находится в `ioc.event.type`,
физически `/ioc/event/type`; переведите старые `event.type` queries. Прежний key
не дублируется, поддержка ECS type/category arrays не добавлялась.

Пропущенный `LegacyLedgerImporter` и актуальный `SqliteUserVersionSchemaMigrator`
используют `ioc.operation.disposition=skipped`. Замените прежний
`event.type=skipped`. Эти два DEBUG record не содержат
`event.outcome=unknown`, поскольку не утверждают такой outcome. Actions
`ledger_import`/`schema_migrate` стабильны.

`LocalImportChangeSignalSource` передаёт failure Throwable без явного `error.type`,
позволяя Boot сформировать один error object со stack trace. Type-only errors
тоже работают. `LoggingMigrationIT` исполняет реальные producer branches и
проверяет JSON lines и encoder/appender statuses.

## Раскрытие данных и redaction

INFO не перечисляет raw IOC. Raw DEBUG/TRACE требует явно разрешённого producer
contract; per-item decision TRACE дополнительно требует одновременно
`ioc.observability.per-item-trace-enabled` и TRACE logger.

IOC `RedactingDiagnosticContextFormatter` заменяет зарегистрированные raw
`indicator`/`value`/`item` context values fingerprints на INFO/WARN/ERROR/FATAL.
На DEBUG/TRACE применяется lexical URL-component sanitizer. В других context
entries обрабатываются и embedded copies этих известных raw values. Helper
убирает распознанный URL user-info/query content, не произвольные secrets, paths
или fragments. Это не URL parser и не автоматическая очистка всех logging channels.

Formatter меняет только rendered context. Исходные diagnostics, free-form
messages и Throwable автоматически не переписываются. `LoggingDiagnosticSink`
передаёт исходный cause backend, который может включить exception messages,
causes и suppressed exceptions в stack trace. Раскрытием во всех этих каналах
владеет producer; очищенное human message не доказывает полной redaction.
Generic codes отображаются буквальными ID, не шаблонами с context.

Operational fields исключают transport host/share/username/password. Managed-import
reports, health и INFO records исключают raw CSV cells, producer filename/path,
snapshot digest и contract-sensitive payload. Используются safe delivery/sequence,
counts и codes. Mask/hide касается только выбранных копий; при квалификации
проверяйте полный output и fallback failure logging.

## Операционные наблюдения

| Contributor | Значение / владелец |
|---|---|
| `ingestionLifecycle` | Recovery barrier и running intake; [ingestion](ingestion.md) |
| `dataProcessingCapacity` | Pending bytes/age/phases, blocked dispatch, writer/workspace pressure; ingestion |
| `lifecycle` | Admission/activation, safe clock и convergence; [record lifecycle](canonical-record-lifecycle.md) |
| `dataframeImport` | Recovery phase, backlog и safe head state; [managed import](dataframe-import.md) |
| `sync` | Source/target/watch observations и durable publication backlog; [sync](sync.md) |
| `smbTransport` | Application-owned resource leases, planned demand и capacity failures; sync |

Health не выполняет работу и не доказывает успех каждого source/transaction.
Clock/convergence и bounded retry могут дать DEGRADED; unsafe clock, failed recovery,
blocked dispatch и durable failed delivery — DOWN по политике владельца.
Чтение `dataframeImport` может упасть на pending head с null retry deadline
(`OPS-10`); observation failure не является rollback signal. См. известные проблемы.

SMB metrics: `ioc.smb.connections.active`, `ioc.smb.sessions.active`,
`ioc.smb.tree.connections.active`, `ioc.smb.session.opens`,
`ioc.smb.operation.failures`, `ioc.smb.resource.exhaustions`. Labels ограничены
logical `endpoint`, `role` и, только для opens, `outcome`. Remote
host/share/path/username/source исключены. Established application usage и planned
demand — не server-wide limits/headroom; capacity degradation снимается успехом
того же internal owner без раскрытия owner как metric label.

Lifecycle actions `lifecycle_admission`, `lifecycle_reconcile`,
`lifecycle_projection`, `lifecycle_retention` используют aggregate data без
per-record IOC. Успешные пустые five-second checks остаются silent; failed work
сохраняет diagnostics и ERROR. Reconcile пишет INFO при expiry records,
projection — при actual/pending work.

Managed import наблюдает `import_start`, `import_claim`, `import_stage`,
`import_promote`, `import_complete` после durable checkpoints и
`import_retry` после retry schedule. Recovery/nonempty retention агрегатны,
пустые checks silent. В логах — delivery/sequence/source, state/outcome,
contract id/version, counts, artifact names и safe error type/code. Terminal
record содержит `ioc.import.disposition` (`terminal`/`quarantine`), а для безопасно
классифицированного parse failure — value-free `ioc.import.failure_reason`.
Candidate tokens, locators, filenames/paths, digests, raw rows и exception messages
не входят в этот adapter. Owning failure boundary доставляет diagnostic один раз;
typed operational event его не дублирует.

## Расширение, источники и сопровождение

Добавляйте diagnostic code вместе с producer: catalog reference ratchet отвергает
orphan constants. Для log field/action проверяйте actual JSON type, collision и
redaction. Новый output реализует `DiagnosticSink`; durable reporting требует
явного occurrence identity/store. APM должен давать настоящие tracing semantics.

- [Runner](../../../platform/platform-etl/src/main/java/com/iocextractor/platform/etl/PipelineRunner.java)
  владеет stage deltas, budgets и policy enforcement.
- [Композиция](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java)
  подключает renderer/sink/tracer;
  [bridge](../../../platform/platform-diagnostics-logging/src/main/java/com/iocextractor/observability/diagnostics/LoggingDiagnosticSink.java)
  владеет category/severity mapping.
- [IOC encoder](../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/IocEcsStructuredLogEncoder.java),
  service JSON tests и standalone recipe tests владеют разными backend paths.
- [Defaults](../../../bootstrap/ioc-app/src/main/resources/application.yml) и
  generated catalogs владеют точными configuration/vocabulary inventories.

Меняйте документ, перевод и затронутые guides при изменениях delivery, budgets,
completion mapping, correlation, disclosure и wire format. Версии библиотек
и release evidence оставляйте их guides/permanent records.

## Связанные документы

- [Event coordination](event-coordination.md) — operational hints и backstops.
- [Security engineering](../../SECURITY-ENGINEERING.md) — logging security policy.
- [ADR-0017](../../ADR/0017-diagnostics-first-class-outcome.md) — diagnostic outcomes.
- [ADR-0018](../../ADR/0018-typed-ecs-structured-logging.md) — typed logging.
- [ADR-0019](../../ADR/0019-spring-boot-4-nested-ecs.md) — nested ECS representation.
