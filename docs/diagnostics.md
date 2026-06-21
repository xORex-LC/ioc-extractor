# Диагностика и логирование

Подсистема диагностики — **отдельная единица логики**. Любой сервис/подсистема
лишь **формирует и отдаёт данные** (код ошибки + структурный контекст), из которых
подсистема строит сообщение, отчёт, запись лога или мета-информацию пайплайна.
Сервисы **не** форматируют строки и не знают, куда уйдёт диагностика.

Это даёт: единые отчёты, **карту ошибок** для пользователей/новых разработчиков,
лёгкое добавление новых ошибок, использование диагностики как **мета-данных** в
конвейере обработки, и развязку продюсеров от способа доставки.

> Статус: **дизайн**. Реализуется как сквозная подсистема (см.
> [cross-cutting.md](cross-cutting.md)); кандидат в модуль `platform/diagnostics`
> ([modularization.md](modularization.md)).

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
- Рендер — порт `DiagnosticRenderer`, адаптер на Spring `MessageSource`
  (i18n-ready). Плейсхолдеры берутся из `context`.

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
| `DiagnosticSink` | out | `LoggingDiagnosticSink`, `ReportDiagnosticSink`, `CollectingSink` (накопитель) |
| `DiagnosticRenderer` | out | `MessageSourceRenderer` (шаблоны+i18n) |
| `MessageCatalog` | out | резолв шаблона по коду (bundle/yaml + дефолт каталога) |

Ядро подсистемы (модель, каталог, Notification/Result, порты) — агностично, без
фреймворков; адаптеры (MessageSource, логирование, отчёты) — снаружи.

## Карта ошибок

Так как каталог перечислим, из него генерируется **карта ошибок** (code →
category → severity → шаблон → где возникает) — для пользователей и новых
разработчиков, и как реестр для отчётов. Генерация — отдельный инструмент/тест,
не рантайм-зависимость.

## Логирование

SLF4J (фасад) + Logback (реализация). Принцип — **чем больше полезного в логе,
тем лучше**, особенно на DEBUG: это трассировка при разработке и основа будущей
интеграции со сборщиками логов.

- **Богатый DEBUG/TRACE:** на каждой стадии — входы/выходы, счётчики, **решения**
  (какой вариант match выбран, какой маркер сработал, что отсеяно дедупом),
  тайминги, per-item детали.
- **MDC (Mapped Diagnostic Context):** к каждой строке лога — контекст
  `runId`/`source`/`stage`/`indicator`, чтобы трассировать прогон сквозь стадии.
- **`LoggingDiagnosticSink`:** мост диагностика → лог; severity диагностики
  маппится на уровень лога, `context` — в структурные поля/MDC.
- **Структурный JSON** (`logstash-logback-encoder`) — под сбор логов
  (ELK/Loki), включается позже; MDC-структура закладывается сразу.

| Уровень | Что логируем |
|---|---|
| ERROR | сбой стадии/прогона, фатальные `Diagnostic` |
| WARN  | восстановимое: пропуск элемента, неоднозначность, dead-letter |
| INFO  | сводка прогона (прочитано/извлечено/записано/отсеяно) |
| DEBUG | стадии, решения, per-item, тайминги — трассировка |
| TRACE | сверхдетально (сырые совпадения, промежуточные значения) |

### Схема структурного лога (версионируемая, расширяемая)

Структурное событие — единый конверт: версия схемы + стабильное ядро + **открытый**
контекст для расширения без смены версии.

```json
{
  "schema_version": "1.0",
  "ts": "2026-06-21T02:18:16.543Z",
  "level": "DEBUG",
  "logger": "c.i.a.service.IocExtractionService",
  "service": "ioc-extractor",
  "app_version": "0.1.0",
  "run_id": "f3c8a1b2",
  "stage": "classify",
  "message": "ambiguous classification",
  "diagnostic": { "code": "CLASSIFY.AMBIGUOUS", "severity": "WARN", "category": "CLASSIFY" },
  "context": { "indicator": "a.b.example.com/x?y=1", "host": "a.b.example.com" },
  "error": { "type": "...", "message": "...", "stack": "..." }
}
```

Версионирование (semver-подобно):
- `schema_version` = `MAJOR.MINOR`.
- **MINOR** — только аддитивные опциональные поля (обратная совместимость;
  потребитель игнорирует незнакомые ключи).
- **MAJOR** — несовместимые изменения ядра (удаление/переименование/смена типа).
- **Без смены версии** — новые ключи внутри `context`/`diagnostic.*` (открытые карты).
- Ядро (`schema_version, ts, level, logger, message, run_id`) — стабильно;
  `stage/diagnostic/error/context` — опциональны.

Реализация — `logstash-logback-encoder` + MDC (`run_id`, `stage`, …) → поля
события. Включается при интеграции со сборщиком; **набор полей фиксируется сейчас**.

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

1. Ядро: `Diagnostic`, `DiagnosticCode` + первые каталоги, `Result`/`Notification`,
   порты `DiagnosticSink`/`DiagnosticRenderer`.
2. `LoggingDiagnosticSink` + MDC + богатый DEBUG в стадиях конвейера.
3. `FailurePolicy` (collect-and-continue) + отчёт по прогону.
4. Карта ошибок (генерация из каталога).
5. (Опц.) структурный JSON-лог; i18n шаблонов.
