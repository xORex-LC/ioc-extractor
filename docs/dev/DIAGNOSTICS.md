# Диагностика обработки данных

Подсистема диагностики — **отдельная единица логики обработки данных**. Любой
сервис/подсистема лишь **формирует и отдаёт данные** (код ошибки + структурный
контекст), из которых подсистема строит сообщение, отчёт, запись в лог или
мета-информацию пайплайна. Сервисы **не** форматируют строки и не знают, куда
уйдёт диагностика.

Это даёт: единые отчёты, **карту ошибок** для пользователей/новых разработчиков,
лёгкое добавление новых ошибок, использование диагностики как **мета-данных** в
конвейере обработки, и развязку продюсеров от способа доставки.

> Статус: **реализовано** по [ADR-0017](../ADR/0017-diagnostics-first-class-outcome.md).
> `platform-diagnostics` даёт модель, generated catalog, impact, bounded outcome,
> `FailurePolicy`, renderer и sink-порты; `PipelineRunner` доставляет
> stage diagnostics exactly once и возвращает summary. Bridge в operational
> logging живёт отдельно в `platform-diagnostics-logging`. Operational logging
> шире диагностики;
> политика логирования фонового сервиса — в [logging.md](LOGGING.md), таксономия
> log events — в [logging-taxonomy.md](LOGGING-TAXONOMY.md).

Категория `SYNC` покрывает final transport-neutral
endpoint/auth/permission/not-found/transient failures, несовпадение remote publish marker
и валидацию локального completed slice. Configuration/credential references
отбиваются strict boot-preflight и не дублируются runtime codes. SMB-типы не
входят в diagnostic contract: адаптер переводит их в `RemoteErrorKind`, а полный
список генерируется в [DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md). Control-plane события ADR 0013
не получают отдельную категорию `EVENTS`: publish/dispatch/admission являются
operational log/health signals, а не самостоятельными диагностическими кодами обработки
данных.

## Принцип разделения

```
Envelope contour:
stage Result<T> → Envelope diagnostics → bounded accumulator
                → DiagnosticSink delta → FailurePolicy → ExtractionResult

Saga contour:
final retry/state failure → durable FAILED transition → DiagnosticSink

DiagnosticCode catalog (code) → generated DIAGNOSTICS-CATALOG.md
```

Эти контуры не смешиваются. В pipeline diagnostic является частью
processing outcome и участвует в policy. В ingest/sync/export saga решение
принимают retry и durable state machine, а diagnostic фиксирует final fact и не
заменяет ledger transition.

## Доменная модель

`Diagnostic` — неизменяемый value object, собирается билдером:

| Поле | Назначение |
|---|---|
| `code` | ссылка на запись каталога (`DiagnosticCode`) |
| `severity` | TRACE / DEBUG / INFO / WARN / ERROR / FATAL |
| `category` | CONFIG / SOURCE / EXTRACTION / CLASSIFY / SINK / INGEST / STORAGE / EXPORT / SYNC / PIPELINE |
| `impact` | ELEMENT / RUN / OPERATION; immutable metadata кода, не выводится из severity |
| `context` | структурные поля-данные (`Map<String,Object>`): source, stage, indicator, value, … |
| `cause` | опциональная причина (`Throwable`) |
| `timestamp` | момент возникновения |

Продюсер строит диагностику данными, **без текста**:

```java
return diagnosticFactory.create(SinkDiagnosticCodes.ROW_MAPPING_FAILED)
        .with(DiagnosticContextKeys.ARTIFACT, artifact)
        .with(DiagnosticContextKeys.TYPE, indicator.type())
        .with(DiagnosticContextKeys.INDICATOR, indicator.value())
        .with("reason", reason)
        .build();
```

## Каталог ошибок (в коде)

Канонический источник истины — **каталог кодов в коде**. Добавить ошибку =
добавить enum-constant в категориальный catalog. Запись несёт стабильный id,
category, default severity, impact, message key и default template. Новый constant
должен сразу иметь production producer: root `CatalogReferenceRatchetTest` держит
allowlist пустым.

```java
public enum ExtractionDiagnosticCodes implements DiagnosticCode {
    INDICATOR_SKIPPED(DiagnosticSeverity.DEBUG, DiagnosticImpact.ELEMENT,
            "extraction.indicator-skipped", "Indicator {indicator} was skipped: {reason}"),
    AMBIGUOUS_VALUE(DiagnosticSeverity.WARN, DiagnosticImpact.ELEMENT,
            "extraction.ambiguous-value", "Value {value} is ambiguous: {reason}");
}
```

Актуальные группы регистрируются в `DiagnosticCatalogs`; их не дублируем в
ручной таблице. Опубликованный generated catalog —
[DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md); он обновляется только через
`-Dioc.docs.update=true` и пинится doc-sync тестом.

## Сообщения и шаблоны (декларативно, для UX)

Текст сообщения **не** в продюсере. Шаблон по коду с плейсхолдерами из контекста:

- **Дефолтный шаблон** — у записи каталога (в коде), чтобы код был самодостаточен.
- **Переопределение/локализация** — декларативно, рядом со спецификой
  (resource-bundle/yaml по ключу кода). Применять **дозированно** — только где
  нужно специфичное под данные сообщение; не перенасыщать.
- Рендер — порт `DiagnosticRenderer`. Seed-реализация (этап 6) —
  `TemplateDiagnosticRenderer` (подстановка `{key}` из `context` в дефолтный
  шаблон каталога). Адаптер на Spring `MessageSource` (i18n) — отложен.

```properties
# messages.properties (UX-слой, опционально; дефолт — в каталоге)
sink.row-mapping-failed = Артефакт {artifact} отклонил индикатор: {reason}
```

## Поток ошибок как данные

- Stage возвращает `Result<T>`/новый `Envelope` с append-only batch diagnostics.
  Одна occurrence либо attach-ится, либо бросается в `DiagnosticException`,
  но не обоими каналами.
- `PipelineRunner` эмитит только delta новой стадии внутри её MDC scope,
  затем применяет `FailurePolicy`. Policy rejection не re-emit'ит уже
  доставленную diagnostic; generic exception стадии превращается в
  `PIPELINE.STAGE_FAILED`.
- Terminal `PIPELINE.DIAGNOSTICS_SUPPRESSED` эмитится ровно один раз внутри
  run scope и вне stage scope на success, policy rejection и stage throw.
- `fail-fast` останавливается на ERROR/FATAL после стадии; `collect-and-continue`
  пропускает ERROR и останавливается на FATAL. Production daemon явно выбирает
  collect; application default остаётся fail-fast.
- `SINK.ROW_MAPPING_FAILED` возникает только из typed `MappingValueException`
  provider/transform-а и несёт safe location (`artifact`, `column`, component
  kind/name, type, ordinal, source и redacted indicator identity). Любой иной
  mapper exception остаётся `PIPELINE.STAGE_FAILED`.
- `BoundedNotification` держит не более `ioc.pipeline.max-diagnostics-per-run`
  высококардинальных ELEMENT/RUN occurrences (default 10 000), не скрывает
  первые ERROR/FATAL и добавляет `PIPELINE.DIAGNOSTICS_SUPPRESSED`. Агрегированные
  OPERATION occurrences проходят вне этого бюджета; summary включает и их, и
  suppressed occurrences.
- `ExtractionResult` возвращает diagnostics, `DiagnosticSummary` и
  `COMPLETED | COMPLETED_WITH_WARNINGS | COMPLETED_WITH_ERRORS`. Для oneshot
  последний status даёт exit code `3`; daemon сохраняет degraded outcome в
  `IngestSourceResult`. Оба driving boundary публикуют run id, status,
  total/suppressed и отдельные severity counts; daemon связывает pipeline с тем
  же durable `ingest_run.run_id`.
- Advisory diagnostics успешной post-commit projection возвращаются через
  `ArtifactProjectionResult`: oneshot присоединяет их к envelope, daemon доставляет и
  объединяет с `ExtractionResult`, startup recovery только доставляет occurrence.
- `SINK.CHARSET_UNMAPPABLE` — одна OPERATION/WARN occurrence на успешно
  установленную lossy CSV projection. Context содержит run/artifact/path/charset
  и точные counts data values/rows/header values, но не исходные значения;
  advisory-only invariant не позволяет failure policy отвергнуть уже committed
  результат.

## Порты и адаптеры

| Порт | Тип | Адаптеры |
|---|---|---|
| `DiagnosticSink` | out | `CollectingDiagnosticSink`, `NoopDiagnosticSink`; `LoggingDiagnosticSink` + non-throwing `ResilientDiagnosticSink` в bridge; durable report/quarantine — seam |
| `DiagnosticRenderer` | out | **реализовано:** `TemplateDiagnosticRenderer` (дефолтные шаблоны каталога); `MessageSourceRenderer` (i18n) — отложен |

Ядро подсистемы (модель, каталог, Notification/Result, порты) — агностично, без
фреймворков; логирование и будущие i18n/report adapters остаются снаружи.

## Карта ошибок

Так как каталог перечислим, из него генерируется **карта ошибок** (code →
category → severity → шаблон → где возникает) — для пользователей и новых
разработчиков, и как реестр для отчётов. Генерация — отдельный инструмент/тест,
не рантайм-зависимость.

Опубликованный generated artifact — [DIAGNOSTICS-CATALOG.md](../DIAGNOSTICS-CATALOG.md).
Категория `EXPORT` полностью покрывает executable boundaries Artifact Emission:
unsupported mode, snapshot read, slice write, invalid manifest/tree, отсутствие
atomic publish, ledger CAS conflict и recovery failure. Код создаётся рядом с
producer-ом: config resolution — bootstrap, snapshot/ledger — JDBC adapter,
filesystem/manifest — CSV adapter, recovery policy — application. Cadence
`SKIPPED` и успешные checkpoints являются operational events, а не ошибками.

SOURCE/EXTRACTION/CLASSIFY/SINK/PIPELINE producers живут на точной
stage/adapter boundary. Preparation отделена от canonical commit: typed row defect
становится ELEMENT diagnostic до side effect, programming/storage defect остаётся
RUN failure. INGEST владеет claim/ledger/dead-letter/recovery boundaries. SYNC маппит
`RemoteErrorKind` после retry exhaustion и durable `FAILED`; diagnostics не попадают
в control-event payload.

## Связь с operational logging

Operational logging шире диагностики: application lifecycle, IO, retry, записи в
артефакты, транзакции и вызовы адаптеров являются log events даже без
диагностического результата. Диагностика подключается к этому потоку через bridge:

```
Diagnostic(code, severity, context, cause)
        │
        ▼
LoggingDiagnosticSink
        │  maps to
        ▼
LogEvent(ECS fields + ioc.diagnostic.* + error.*)
```

Правила:

- `Diagnostic` не диктует `event.action`; он только добавляет
  `ioc.diagnostic.*` и, при наличии причины, `error.*`.
- `LoggingDiagnosticSink` живёт в bridge-модуле (`platform-diagnostics-logging`),
  использует таксономию из [logging-taxonomy.md](LOGGING-TAXONOMY.md), но не
  является главным каналом всех логов.
- Обычные SLF4J log events могут существовать без `DiagnosticCode`.
- Политика вывода (`console`, rolling file, ECS JSON, retention) описана в
  [logging.md](LOGGING.md).

## Паттерны и референсы

**Паттерны:** Catalog/Registry · Value Object + Builder · Notification (Fowler) ·
Result/Either · Strategy (`FailurePolicy`, `DiagnosticRenderer`) · Ports & Adapters ·
Observer/Event (sinks) · Template/MessageFormat.

**Референсы:**
- **RFC 9457 Problem Details** + **`zalando/problem`** — структурные данные ошибки
  и расширяемый реестр типов.
- **Apache Calcite `Resources`** — каталог ошибок в коде + шаблоны сообщений
  (коды как методы, `@BaseMessage`, параметры в рантайме).
- **Spring `MessageSource` / `ProblemDetail`** — декларативный резолв шаблонов, i18n.
- **M. Fowler — «Notification»** — накопление результатов/ошибок.

## Связи

- Реализует «ошибки/диагностику за портами» из [cross-cutting.md](CROSS-CUTTING.md);
  `FailurePolicy`/dead-letter применяются и в [ingestion.md](ingestion.md).
- Соблюдает [principles.md](../PRINCIPLES.md): каталог/инструменты — тонкий код,
  шаблоны/специфика сообщений — декларативны и дозированы.
- Кандидаты `platform/diagnostics` — в [modularization.md](../MODULARIZATION.md).

## Этапы внедрения

1. **Сделано (этап 6).** Ядро: `Diagnostic`, `DiagnosticCode` + первые каталоги,
   `Result`/`Notification`, порты `DiagnosticSink`/`DiagnosticRenderer` +
   `CollectingDiagnosticSink`/`NoopDiagnosticSink`/`TemplateDiagnosticRenderer`.
2. **Сделано (этап 6).** `FailurePolicy` (`fail-fast` | `collect-and-continue`,
   чистая) + `Notification.throwIfRejected` на orchestration boundary.
3. **Сделано:** карта ошибок (генерация из каталога, doc-sync тест). i18n шаблонов
   (`MessageCatalog`/`MessageSource`) — **отложено**.
4. **Сделано (ADR-0017):** impact, producer migration, exactly-once delivery,
   configurable bounded outcome, completion status, preparation checkpoint,
   redaction/resilience и ingest/sync final diagnostics. Reference ratchet
   allowlist пуст.

> **Seam:** durable report/quarantine и stable occurrence identity не входили в
> OBS-D1. Они зарегистрированы как OBS-4 в [KNOWN-ISSUES.md](../KNOWN-ISSUES.md) и
> не имитируются best-effort logging sink-ом.

Мост в логи (`LoggingDiagnosticSink`, ECS/MDC) — **отдельная подсистема
observability** ([logging.md](LOGGING.md)); реализуется своим этапом, а не здесь.
