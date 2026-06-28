# Диагностика обработки данных

Подсистема диагностики — **отдельная единица логики обработки данных**. Любой
сервис/подсистема лишь **формирует и отдаёт данные** (код ошибки + структурный
контекст), из которых подсистема строит сообщение, отчёт, запись в лог или
мета-информацию пайплайна. Сервисы **не** форматируют строки и не знают, куда
уйдёт диагностика.

Это даёт: единые отчёты, **карту ошибок** для пользователей/новых разработчиков,
лёгкое добавление новых ошибок, использование диагностики как **мета-данных** в
конвейере обработки, и развязку продюсеров от способа доставки.

> Статус: **реализовано базовое ядро** в `platform/platform-diagnostics`:
> модель, каталоги кодов, `Result`/`Notification`, `FailurePolicy`, renderer и
> sink-порты. Bridge в operational logging живёт отдельно в
> `platform/platform-diagnostics-logging`. Operational logging шире диагностики;
> политика логирования фонового сервиса — в [logging.md](logging.md), таксономия
> log events — в [logging-taxonomy.md](logging-taxonomy.md).

Категория `SYNC` покрывает transport-neutral endpoint/auth/permission/not-found/transient
ошибки, credential/config failures и несовпадение remote publish marker. SMB-типы не входят
в diagnostic contract: адаптер переводит их в `RemoteErrorKind`, а полный опубликованный
список генерируется в [diagnostic-catalog.md](diagnostic-catalog.md).

## Принцип разделения

```
producers (domain / application / adapters)
    │  собирают Diagnostic(code, context)         ← только данные
    ▼
Result<T> / Notification ──▶ FailurePolicy (fail-fast | collect-and-continue)
    │
    ├──▶ DiagnosticRenderer (port) ──▶ MessageSource: шаблон[code] + context → текст
    └──▶ DiagnosticSink (port) ──▶ LoggingSink | ReportSink | PipelineMetadata
DiagnosticCode catalog (КОД) ──▶ генерация «карты ошибок» (docs)
```

## Доменная модель

`Diagnostic` — неизменяемый value object, собирается билдером:

| Поле | Назначение |
|---|---|
| `code` | ссылка на запись каталога (`DiagnosticCode`) |
| `severity` | TRACE / DEBUG / INFO / WARN / ERROR / FATAL |
| `category` | SOURCE / EXTRACTION / CLASSIFY / SINK / CONFIG / VALIDATION / … |
| `context` | структурные поля-данные (`Map<String,Object>`): source, stage, indicator, value, … |
| `cause` | опциональная причина (`Throwable`) |
| `timestamp` | момент возникновения |

Продюсер строит диагностику данными, **без текста**:

```java
// design sketch — продюсер отдаёт данные, не сообщение
return Diagnostic.of(ExtractionCodes.AMBIGUOUS_CLASSIFICATION)
        .with("indicator", value)
        .with("host", host)
        .with("candidates", List.of("variant2", "variant4"))
        .build();
```

## Каталог ошибок (в коде)

Канонический источник истины — **каталог кодов в коде**. Добавить ошибку =
добавить запись в каталог. Каталог **перечислим** → из него генерируется карта
ошибок (документация). Запись кода несёт: стабильный id, категорию, severity по
умолчанию, ключ шаблона сообщения по умолчанию.

```java
// design sketch — группа кодов как перечислимый каталог
public enum ExtractionCodes implements DiagnosticCode {
    PATTERN_INVALID   (Category.CONFIG,     Severity.FATAL, "extraction.pattern.invalid"),
    AMBIGUOUS_CLASSIFICATION(Category.CLASSIFY, Severity.WARN, "extraction.classify.ambiguous"),
    INDICATOR_SKIPPED (Category.EXTRACTION, Severity.DEBUG, "extraction.indicator.skipped");
    // id, category, defaultSeverity, messageKey
}
```

Группы каталога по подсистемам: `SourceCodes`, `ExtractionCodes`, `ClassifyCodes`,
`SinkCodes`, `ConfigCodes`, … Стабильный id (напр. `EXTRACTION.INDICATOR_SKIPPED`)
используется в логах, отчётах и как ключ карты ошибок.

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
extraction.classify.ambiguous = Неоднозначная классификация {indicator}: кандидаты {candidates}
```

## Поток ошибок как данные

- **Result/Outcome<T>** = значение + `List<Diagnostic>`; и/или **Notification**
  (Fowler) для накопления по прогону/элементу.
- **FailurePolicy** (Strategy, декларативно): `fail-fast` | `collect-and-continue`.
  Один «битый» элемент не роняет прогон — диагностики собираются и идут в отчёт.
- **Фатальное** — `DiagnosticException` (несёт `Diagnostic`), ловится/транслируется
  на границах адаптеров (адаптерные исключения не текут в домен).
- **Мета-инфо пайплайна:** стадии прикрепляют диагностики к результату
  (`skipped`, `ambiguous`, `refanged`, `deduped`) — это вход для отчётов и
  наблюдаемости.

## Порты и адаптеры

| Порт | Тип | Адаптеры |
|---|---|---|
| `DiagnosticSink` | out | **реализовано:** `CollectingDiagnosticSink`, `NoopDiagnosticSink`; `LoggingDiagnosticSink` — через bridge-модуль observability; `ReportDiagnosticSink` — будущий |
| `DiagnosticRenderer` | out | **реализовано:** `TemplateDiagnosticRenderer` (дефолтные шаблоны каталога); `MessageSourceRenderer` (i18n) — отложен |
| `MessageCatalog` | out | резолв шаблона по коду (bundle/yaml + дефолт каталога) — **отложен до i18n-шага** (см. [dev/0008](dev/0008-stage-6-8-implementation-followups.md)) |

Ядро подсистемы (модель, каталог, Notification/Result, порты) — агностично, без
фреймворков; адаптеры (MessageSource, логирование, отчёты) — снаружи.

## Карта ошибок

Так как каталог перечислим, из него генерируется **карта ошибок** (code →
category → severity → шаблон → где возникает) — для пользователей и новых
разработчиков, и как реестр для отчётов. Генерация — отдельный инструмент/тест,
не рантайм-зависимость.

Опубликованный generated artifact — [diagnostic-catalog.md](diagnostic-catalog.md).
Категория `EXPORT` полностью покрывает executable boundaries Artifact Emission:
unsupported mode, snapshot read, slice write, invalid manifest/tree, отсутствие
atomic publish, ledger CAS conflict и recovery failure. Код создаётся рядом с
producer-ом: config resolution — bootstrap, snapshot/ledger — JDBC adapter,
filesystem/manifest — CSV adapter, recovery policy — application. Cadence
`SKIPPED` и успешные checkpoints являются operational events, а не ошибками.

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
  использует таксономию из [logging-taxonomy.md](logging-taxonomy.md), но не
  является главным каналом всех логов.
- Обычные SLF4J log events могут существовать без `DiagnosticCode`.
- Политика вывода (`console`, rolling file, ECS JSON, retention) описана в
  [logging.md](logging.md).

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

- Реализует «ошибки/диагностику за портами» из [cross-cutting.md](cross-cutting.md);
  `FailurePolicy`/dead-letter применяются и в [ingestion.md](ingestion.md).
- Соблюдает [principles.md](principles.md): каталог/инструменты — тонкий код,
  шаблоны/специфика сообщений — декларативны и дозированы.
- Кандидаты `platform/diagnostics` — в [modularization.md](modularization.md).

## Этапы внедрения

1. **Сделано (этап 6).** Ядро: `Diagnostic`, `DiagnosticCode` + первые каталоги,
   `Result`/`Notification`, порты `DiagnosticSink`/`DiagnosticRenderer` +
   `CollectingDiagnosticSink`/`NoopDiagnosticSink`/`TemplateDiagnosticRenderer`.
2. **Сделано (этап 6).** `FailurePolicy` (`fail-fast` | `collect-and-continue`,
   чистая) + `Notification.throwIfRejected` на orchestration boundary.
3. **Сделано:** карта ошибок (генерация из каталога, doc-sync тест). i18n шаблонов
   (`MessageCatalog`/`MessageSource`) — **отложено**.

> **Открытый долг:** диагностика частично остаётся инфраструктурным контуром:
> каталоги и bridge есть, `PipelineRunner` умеет останавливать поток через
> `DiagnosticException`, но большинство production producer-ов всё ещё бросают
> `IocExtractorException` без построения typed `Diagnostic`. План интеграции —
> [dev/0008](dev/0008-stage-6-8-implementation-followups.md).

Мост в логи (`LoggingDiagnosticSink`, ECS/MDC) — **отдельная подсистема
observability** ([logging.md](logging.md)); реализуется своим этапом, а не здесь.
