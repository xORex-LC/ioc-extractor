# 0018 — Типизированные ECS structured logs: event fields вне MDC

## Статус

**Принято 2026-07-16, не реализовано.** Задаёт закрытие **OBS-D3** из
[KNOWN-ISSUES](../KNOWN-ISSUES.md): числовые и boolean-поля operational logs
должны сохранять JSON-типы до ECS-файла, а не превращаться в строки на границе
SLF4J MDC.

ADR уточняет и частично supersedes [0007](0007-logging-observability.md):

- Решение 4 сохраняется для **ambient correlation context**, но больше не
  распространяется на event-local structured fields;
- следствие про `co.elastic.logging:logback-ecs-encoder` заменяется встроенным
  Spring Boot ECS structured logging;
- `event.dataset` остаётся полем события, не correlation key; для единственного
  текущего dataset оно задаётся статически как `ioc-extractor`. Переход к
  нескольким datasets потребует отдельного решения и удаления static member,
  чтобы не получить duplicate key;
- источник истины по run/source/stage correlation по-прежнему находится в
  `Envelope.meta`/driving boundary, а MDC остаётся только его производным
  transport-представлением.

[0017](0017-diagnostics-first-class-outcome.md) не supersede'ится:
diagnostics-first-class outcome, severity→level mapping, redaction и resilient
delivery не меняются. Этот ADR реализует отложенный там typed logging channel.

Связанные опубликованные документы:

- [LOGGING.md](../dev/LOGGING.md);
- [LOGGING-TAXONOMY.md](../dev/LOGGING-TAXONOMY.md);
- [LOGGING-CATALOG.md](../LOGGING-CATALOG.md);
- [DIAGNOSTICS.md](../dev/DIAGNOSTICS.md).

## Контекст

### Текущая цепочка теряет тип

`LogEvent` принимает `Object`:

```java
LogEvents.info(log)
        .field(LogField.IOC_ROWS, 58)
        .durationNanos(18_324_056L)
        .message("stage completed")
        .log();
```

На входе `58` является `Integer`, а duration — `Long`. Затем:

```text
LogEvent.fields: Map<String, Object>
  -> LogEvent.scope()
  -> MdcScope.put(key, value)
  -> MDC.put(key, String.valueOf(value))
  -> EcsEncoder.serializeMDC(...)
  -> JSON string
```

Итоговый daemon-файл содержит:

```json
{
  "event.duration": "18324056",
  "ioc.rows": "58",
  "ioc.sync.shed_to_reconcile": "true"
}
```

Причина структурная, а не локальная ошибка одного encoder setting:

1. контракт SLF4J MDC — `Map<String, String>`;
2. текущий `logback-ecs-encoder 1.8.0` сериализует MDC через
   `String.valueOf(...)` и JSON string;
3. `additionalFields` encoder-а статичны на весь appender и не являются
   per-event transport;
4. собственный protected `addCustomFields(...)` hook потребовал бы
   сопровождения framework-specific serializer-а.

### Долг вырос после D1

На момент регистрации OBS-D3 заметными были прежде всего `event.duration` и
`ioc.rows`. После [0017](0017-diagnostics-first-class-outcome.md) terminal
completion публикует diagnostic total/suppressed и шесть severity counts.
Sync/export/storage также публикуют queue depth, file counts, revisions,
schema versions и boolean admission outcomes.

В `LogField` сейчас 58 полей. Существенная часть имеет числовую либо boolean
семантику:

- `event.duration`;
- `ioc.span.start`, `ioc.span.end`;
- `ioc.rows`;
- schema/migration/identity versions;
- export revision и control-event version;
- sync files/queue/abandoned counts;
- diagnostic total/suppressed и per-severity counts;
- `ioc.sync.shed_to_reconcile`.

Дополнительно два JDBC producer-а обходят taxonomy через публичный
`LogEvent.field(String, Object)`:

- `ioc.legacy_import.*`;
- `ioc.storage.sqlite.*`.

Следовательно, точечная правка известных на сегодня числовых ключей не
закрывает класс проблемы: новый producer сможет снова опубликовать stringly
numeric field.

### Проявление на Elasticsearch boundary

До появления Elasticsearch-потребителя строковое число почти незаметно:
JSON корректен, Python-парсер читает значение, текстовый поиск работает.

После подключения Elasticsearch возможны два режима:

1. При заранее установленном numeric mapping quoted number может быть coerced,
   потому что Elasticsearch по умолчанию разрешает string→number coercion.
   Это не контракт: coercion можно отключить, malformed value отвергает документ,
   а `_source` всё равно сохраняет исходную форму.
2. При dynamic mapping первый документ со строкой создаёт `keyword`/string
   mapping. После этого numeric document конфликтует с существующим mapping,
   а `avg`, `sum`, percentiles, numeric sort и range queries недоступны без
   rollover/reindex.

Нельзя перекладывать исправление на ingest pipeline или runtime fields каждого
потребителя. Producer обязан публиковать корректный JSON scalar type.

## Движущие силы и ограничения

Решение обязано одновременно выполнить следующие требования:

1. Сохранить тип `String`/integral `long`/`boolean` от `LogEvent.field(...)` до
   JSON без эвристического повторного парсинга.
2. Оставить run/source/stage/correlation в MDC, чтобы поля попадали в обычные
   Spring/JDBC/adapter logs, которые не используют `LogEvent`.
3. Не переносить Spring Boot, Logback или JSON serializer в domain/application
   и не добавлять framework dependency в `platform-observability`.
4. Сделать тип поля частью generated taxonomy и failing-test контракта.
5. Не допускать произвольные незарегистрированные structured keys.
6. Не публиковать два одинаковых JSON key из MDC и event-local transport.
7. Сохранить корректность при Logback `AsyncAppender`: event-local данные
   должны быть частью `ILoggingEvent`, а не считываться encoder-ом из отдельного
   `ThreadLocal`.
8. Не увеличивать стоимость disabled DEBUG/TRACE за счёт formatter/JSON work.
9. Сохранить rolling-file, redaction, diagnostic exactly-once и severity→level
   контракты.
10. Явно описать wire-format migration и судьбу уже созданных Elasticsearch
    indices.

## Решение 1 — два канала с разной семантикой

Structured logging разделяется на **ambient context** и **event-local fields**:

```text
Envelope/driving metadata                    Operation result
run/source/stage/correlation                 duration/rows/counts/outcome data
             |                                            |
             v                                            v
        MdcScope                                     LogEvent
   Map<String, String>                         Map<LogField, Object>
             |                                            |
             |                              SLF4J 2 LoggingEventBuilder
             |                                  addKeyValue(key, value)
             +-----------------------+--------------------+
                                     v
                              ILoggingEvent
                         MDC + typed key/value pairs
                                     |
                                     v
                     Spring Boot ECS structured formatter
                                     |
                                     v
                           JSON strings/numbers/booleans
```

### Ambient context — MDC

MDC хранит только текстовые данные, принадлежащие всему execution scope:

- `ioc.run.id`;
- `ioc.source.id`;
- `ioc.source.path`;
- `ioc.source.content_hash`;
- `ioc.mode`;
- `ioc.stage`;
- control-event/correlation identifiers;
- transport endpoint/target identity, если они описывают весь handler scope.

Это позволяет стороннему:

```java
logger.info("connection opened");
```

получить run/source correlation без зависимости на project `LogEvent`.

### Event-local structured fields — SLF4J key/value pairs

Длительности, counts, revisions, boolean outcomes и другие свойства одной
log occurrence передаются через стандартный SLF4J 2 fluent API:

```java
logger.atInfo()
        .addKeyValue("event.action", "artifact_project")
        .addKeyValue("ioc.rows", 58L)
        .addKeyValue("event.duration", 18_324_056L)
        .log("artifact projection written");
```

`KeyValuePair.value` остаётся `Object` внутри `ILoggingEvent`. Поэтому
Logback async hand-off переносит тип вместе с event snapshot и не зависит от
состояния producer thread после возврата из logger call.

MDC не становится «legacy channel»: он остаётся правильным инструментом для
ambient correlation, но перестаёт быть универсальным transport для всех
structured values.

## Решение 2 — `LogField` становится исполнимой схемой

Добавляется минимальный закрытый словарь JSON scalar types:

```java
public enum LogValueType {
    STRING,
    LONG,
    BOOLEAN
}
```

`DOUBLE`, arrays и arbitrary objects не добавляются до первого реального
producer/consumer contract. Текущие operational fields обходятся тремя
типами; преждевременная универсальная JSON type system нарушила бы YAGNI.

`LogField` получает обязательный `valueType`:

```java
EVENT_ACTION("event.action", STRING, "..."),
EVENT_DURATION("event.duration", LONG, "..."),
IOC_ROWS("ioc.rows", LONG, "..."),
IOC_SYNC_SHED_TO_RECONCILE(
        "ioc.sync.shed_to_reconcile", BOOLEAN, "...");
```

Тип является такой же стабильной частью downstream contract, как key:

- смена `STRING → LONG` после публикации требует index rollover/reindex;
- переименование key ломает сохранённые запросы;
- новый field добавляется только вместе с первым production producer;
- отсутствие типа запрещено structural test-ом.

`LOGGING-CATALOG.md` получает колонку `JSON type`:

```text
| Field | Namespace | JSON type | Description |
```

### Нормализация значений

`LogField`/маленький package-private normalizer задаёт один контракт:

- `STRING`: принимаются `CharSequence`, enum, `Path`, UUID и другие
  явно разрешённые textual values; в event попадает строковое представление;
- `LONG`: принимаются только целые `Byte`, `Short`, `Integer`, `Long`;
  в event всегда попадает `Long`;
- `BOOLEAN`: принимается только `Boolean`;
- `null`: field отсутствует;
- fractional number, arbitrary `Map/List` или несовместимый value type —
  programming defect, а не повод silently stringify.

Не вводится registry/Strategy по типам: closed switch из трёх вариантов проще,
дешевле и честнее текущего масштаба. Новый JSON scalar type добавляется
осознанным изменением enum, generator и tests.

### Удаление string escape hatch

Публичный:

```java
LogEvent.field(String key, Object value)
```

удаляется. Все production fields проходят через `LogField`.

Добавляются зарегистрированные JDBC fields:

- `ioc.legacy_import.name`;
- `ioc.legacy_import.scanned`;
- `ioc.legacy_import.imported`;
- `ioc.legacy_import.skipped`;
- `ioc.legacy_import.failed`;
- `ioc.storage.sqlite.tuning`;
- `ioc.storage.sqlite.max_pool_size`;
- `ioc.storage.sqlite.write_max`;
- `ioc.storage.sqlite.read_max`;
- `ioc.storage.sqlite.busy_timeout_ms`.

Producer audit перед реализацией удаляет либо подключает constants без
production producer. На момент ADR подтверждены:

- `ioc.affected_rows`;
- `ioc.sync.running`;
- `ioc.sync.oldest_age`.

`ioc.sync.oldest_age` не может оставаться опубликованным числом без единицы
измерения. Если producer появится позже, key обязан явно зафиксировать unit
либо использовать документированный ECS/custom unit contract.

## Решение 3 — `LogEvent` остаётся facade, transport меняется внутри

Production call sites сохраняют текущий fluent project API:

```java
LogEvents.info(log)
        .action(EventAction.ARTIFACT_PROJECT)
        .outcome(EventOutcome.SUCCESS)
        .field(LogField.IOC_ROWS, rows)
        .durationNanos(durationNanos)
        .message("artifact projection written")
        .log();
```

SLF4J fluent API не протекает по 20+ producer classes. `LogEvent` остаётся
Facade/Builder с тремя обязанностями:

1. проверить и нормализовать value по `LogField`;
2. при enabled level построить `LoggingEventBuilder` и добавить typed pairs;
3. выполнить один logger call с optional cause.

Он не сериализует JSON, не знает ECS template и не зависит от Logback/Spring.

### Disabled-level behavior

Проверка `logger.isXEnabled()` выполняется до создания backend builder и до
добавления key/value pairs. `LogEvent` по-прежнему держит маленький bounded
`LinkedHashMap` полей, но disabled event:

- не создаёт JSON;
- не вызывает value suppliers/formatter;
- не создаёт framework-specific structured arguments/markers.

Per-item TRACE уже имеет внешний двойной gate из [0017], поэтому typed transport
не добавляет work при выключенном TRACE.

### Ошибка типа

Несовместимый value не превращается обратно в строку. Это programming defect,
который должен быть пойман unit/architecture tests и fail fast при прямом
вызове API. Попытка «сохранить лог любой ценой» создала бы schema corruption,
ради устранения которой принят ADR.

Resilience `DiagnosticSink` не меняется: его внешний decorator по-прежнему
изолирует failure observational bridge. Этот ADR не вводит общий swallow для
всех SLF4J-вызовов и не скрывает ошибки программирования.

## Решение 4 — MDC принимает только string-typed fields

`MdcScope.put(LogField, Object)` проверяет:

```text
field.valueType == STRING
```

и отказывает для `LONG`/`BOOLEAN`. Публичный `put(String, Object)` удаляется;
raw-key запись остаётся только private implementation detail самого scope.
Taxonomy нельзя обойти произвольным key.

Для merge-протокола `LogEvent` добавляется узкая операция:

```java
MdcScope.hide(LogField field)
```

Она временно удаляет уже существующий MDC key и восстанавливает его в
`close()`, но не позволяет записать новое значение и не является обходом
field-type проверки.

Это делает разделение двух каналов исполнимым: новый numeric field невозможно
случайно вернуть в string transport.

Текущие numeric scope fields мигрируют:

- `ioc.event.version` перестаёт быть ambient MDC и добавляется к конкретным
  `event_publish`/`event_dispatch` occurrences;
- `ioc.sync.files` перестаёт жить во всём remote-change handler scope и
  публикуется только событиями, где count действительно рассчитан и имеет
  смысл.

Nested сторонние logs сохраняют event/run/correlation identity, но не получают
случайные event-local counters. Это осознанное уточнение семантики, а не потеря
observability.

## Решение 5 — event-local field побеждает одноимённый MDC key

Одинаковый key не должен дважды появляться в JSON. Порядок JSON members у
конкретного formatter-а не считается публичной merge-policy.

Контракт:

```text
event-local field > ambient MDC field
```

Перед logger call `LogEvent` через `MdcScope.hide(...)` временно снимает из MDC
только keys, которые присутствуют в event-local map, затем восстанавливает их
через nested `MdcScope.close()`. В результате:

- `ILoggingEvent` получает один key;
- event-local typed value не деградирует до MDC string;
- внешний MDC восстанавливается после log call;
- при `AsyncAppender` уже созданный event snapshot содержит правильные MDC и
  key/value pairs;
- разные потоки не пересекаются, потому что MDC thread-local.

Это защитный механизм для boundary logs, которые явно повторяют `ioc.run.id`
или другое ambient поле. Он не является приглашением дублировать context во
всех producers.

## Решение 6 — Spring Boot 3.4 built-in ECS formatter

Parent обновляется с Spring Boot `3.3.5` до **`3.4.13`** отдельным
behavior-neutral build commit. Переход сразу через две minor-линии не делается:
3.4 — минимальный upgrade, который предоставляет нужный standard transport и
снижает миграционный риск.

После зелёного upgrade gate:

- удаляется `co.elastic.logging:logback-ecs-encoder`;
- `co.elastic.logging.logback.EcsEncoder` заменяется на
  `org.springframework.boot.logging.logback.StructuredLogEncoder`;
- формат задаётся как `ecs`;
- существующий `RollingFileAppender`, file name, size/time rotation,
  `maxHistory` и `totalSizeCap` сохраняются;
- console остается human-readable, если отдельное решение не переведёт его в
  structured format.

Spring Boot formatter выбран потому, что:

- официально читает SLF4J fluent key/value pairs;
- сохраняет Object values в JSON;
- поддерживает ECS без собственного serializer-а;
- входит в уже используемый framework baseline;
- имеет documented JSON customization seam.

Logging system инициализируется до `ApplicationContext`. Поэтому encoder не
является Spring bean, не получает application services и не настраивается
через `AppConfig`. Его composition принадлежит `logback-spring.xml` и ранним
`logging.structured.*` properties.

### Static ECS/service fields

Новый formatter обязан сохранить:

- `service.name=ioc-extractor`;
- service version из одного build/application property source;
- `event.dataset=ioc-extractor`;
- daemon file path;
- exception type/message/stack trace;
- logger/thread identity.

`event.dataset` задаётся через Boot structured JSON static member/customizer,
а не через MDC. При единственном dataset это appender-level contract. Если
появятся разные datasets, отдельное решение уберёт static member и передаст
dataset через event-local typed channel; два источника одного key одновременно
не допускаются.

## Решение 7 — wire-format migration является публичным изменением

Переход encoder-а не считается внутренним рефакторингом. Меняются как минимум:

- numeric/boolean values: JSON strings → JSON numbers/booleans;
- `ecs.version`: текущий Elastic encoder публикует `1.2.0`, Boot 3.4 formatter
  публикует `8.11`;
- formatter может добавить стандартные ECS members, например `process.pid`;
- порядок JSON members не гарантируется;
- downstream parser, ожидающий только strings, должен принять JSON scalar
  types.

Стабильным контрактом считаются:

- field names;
- JSON scalar type из `LogField`;
- action/outcome values;
- units и семантика поля;
- presence rules, где они явно задокументированы.

Порядок members и whitespace контрактом не являются. Тесты парсят JSON tree,
а не сравнивают сырую строку целиком.

## Решение 8 — `event.severity` не добавляется механически

Typed channel технически позволяет опубликовать ECS `event.severity: long`, но
закрытие OBS-D3 не должно одновременно изобретать новую severity scale.

ECS `event.severity` описывает числовую severity **по шкале event source** и не
обязана совпадать с `log.level`. В текущем проекте:

- diagnostics уже публикуют точную enum severity в
  `ioc.diagnostic.severity`;
- `LoggingDiagnosticSink` отображает её в `log.level`;
- generic operational events не имеют отдельной source severity.

Поэтому `event.severity` в рамках ADR не публикуется. Если появится запрос
дашборда или внешний severity contract, отдельное решение задаст стабильную
шкалу и область применения. Наличие typed transport не является достаточным
основанием добавлять поле.

## Решение 9 — Elasticsearch template остаётся явным activation seam

Корректный JSON type предотвращает создание нового неправильного dynamic
mapping, но **не исправляет уже существующий index**:

- field, уже созданный как `keyword`, не станет `long`;
- первые numeric documents после producer fix получат mapping conflict;
- требуется новый index/data stream generation, rollover либо reindex.

В текущем проекте Elasticsearch не является supported deployment consumer.
Поэтому OBS-D3 не добавляет неиспользуемый installer/API client и не притворяется,
что template уже установлен.

При появлении первого поддерживаемого Elasticsearch consumer активируется
зарегистрированный seam **OBS-5**:

1. из `LogField` metadata генерируется project component template;
2. project `STRING` fields маппятся как `keyword`, `LONG` — `long`,
   `BOOLEAN` — `boolean`;
3. ECS-owned fields не дублируются слепо, а валидируются против выбранной ECS
   версии/template;
4. numeric fields могут получить `meta.unit`/`meta.metric_type`, когда их
   семантика действительно определена;
5. template устанавливается **до** создания первого data stream/index;
6. обновление несовместимого типа требует versioned template + rollover;
7. для legacy indices публикуется reindex/runbook, producer не пытается
   «исправить» mapping повторной отправкой.

Владение:

- `platform-observability` владеет schema metadata;
- generator может жить рядом с logging catalog doc-sync;
- Elasticsearch template/install/runbook принадлежит `packaging`/будущему
  ingest adapter, не core и не `LogEvent`.

Этот seam фиксируется сейчас, чтобы типизация taxonomy не была спроектирована
только под текущий JSON-файл и не потеряла будущую mapping boundary.

## Границы модулей и ответственности

| Модуль | Ответственность |
|---|---|
| `platform-observability` | `LogValueType`, typed `LogField`, `LogEvent` facade, string-only `MdcScope`, taxonomy/catalog tests |
| `platform-diagnostics-logging` | diagnostic→`LogEvent` bridge; severity/redaction contract без знания encoder-а |
| adapters | только выбирают `LogField` и передают typed operational values |
| `bootstrap/ioc-app` | Spring Boot version, `logback-spring.xml`, early structured logging properties, real encoder integration tests |
| `docs/` | wire contract, generated field catalog, migration consequences |
| future packaging/ES adapter | component template installation, rollover/reindex runbook |

`ioc-domain`, `ioc-application`, `platform-etl`, diagnostics model и control-event
contracts не получают Spring/Logback imports. Нового business port не вводится:
SLF4J уже является platform-observability boundary, а formatter — bootstrap
infrastructure detail.

Собственный `TypedEcsEncoder`, JSON serializer, `ThreadLocal<Map<String,Object>>`
или Spring bean для logging initialization не создаются.

## Concurrency и performance

### Concurrency

- MDC остаётся thread-local и закрывается LIFO через `MdcScope`.
- Typed pairs принадлежат конкретному `ILoggingEvent`.
- Async appender получает frozen event data, не читает producer ThreadLocal.
- Collision scope восстанавливает только изменённые keys.
- Два синхронных run на разных daemon threads не смешивают correlation.
- Не обещается автоматическое распространение MDC через новые executor/reactive
  boundaries; при их появлении нужен явный context propagation design.

### Allocation/runtime

Для одного enabled event выполняется один проход по небольшому field map:

```text
O(number of event fields)
```

Обычно это 2–10 entries. Нет:

- повторного JSON parse;
- regex/type guessing;
- глобального key registry lookup на каждый field;
- дополнительной полной копии log message;
- reflection в hot path.

`Long` boxing при `addKeyValue` неизбежен в Object-based SLF4J API и ничтожен
относительно JSON formatting/I/O. Специальные primitive collections или
code-generation не вводятся без профилированного bottleneck.

Не создаётся unstable timing gate. Allocation/runtime smoke выполняется на
stage DEBUG events и enabled per-item TRACE synthetic input; оптимизация
проводится только при измеренном regression.

## Отклонённые варианты

### 1. Оставить MDC и парсить известные keys в formatter-е

Schema-aware `String → Long/Boolean` conversion мог бы исправить JSON с
минимумом call-site изменений, но тип сначала уничтожается, затем
восстанавливается. Это:

- скрывает transport defect;
- переносит type error в поздний encoder lifecycle;
- оставляет ambient и event-local semantics смешанными;
- делает custom formatter обязательным навсегда.

Допустимо только как временный migration bridge, не как target architecture.

### 2. Парсить любое numeric-looking MDC value

Отклонено: run/event/slice identifiers могут выглядеть числовыми, но являются
`keyword`. Эвристика меняет schema в зависимости от значения первого документа.

### 3. Наследоваться от Elastic `EcsEncoder`

Технически возможно через `addCustomFields(...)`, но требует:

- отдельного adapter/module или Logback/Elastic dependency в platform;
- собственного scalar JSON escaping/serialization;
- collision policy с уже сериализованным MDC;
- сопровождения при upgrade encoder-а.

Это fallback только если Spring Boot upgrade окажется заблокированным
подтверждённой несовместимостью.

### 4. Перейти на `logstash-logback-encoder`

Библиотека умеет typed structured arguments/key-value pairs, но добавляет новую
dependency family и требует вручную поддерживать ECS shape. При наличии
встроенного Boot ECS formatter это лишняя инфраструктура.

### 5. Передавать typed map через отдельный ThreadLocal

Отклонено: encoder может работать на другом async thread, а скрытый context не
является частью `ILoggingEvent`. Решение ломается при смене appender topology.

### 6. Добавить параллельные keys `ioc.rows_long`

Отклонено: создаёт вечный двойной wire contract, усложняет dashboards и не
защищает новые fields.

### 7. Полностью отказаться от MDC

Отклонено: сторонние framework logs потеряют run/source correlation, а каждый
adapter будет вынужден вручную повторять context в каждой occurrence.

### 8. Переход на Micrometer Observation/OpenTelemetry logs

Metrics/tracing не заменяют operational event log и не решают текущую
SLF4J→JSON типизацию сами по себе. Добавлять новый telemetry stack без
consumer/SLO — нарушение YAGNI.

## Обязательные инварианты и тесты

### Taxonomy/API

- каждый `LogField` имеет непустой key/description и `LogValueType`;
- keys уникальны и проходят namespace rules;
- `LOGGING-CATALOG.md` совпадает с generated catalog с колонкой JSON type;
- public arbitrary-key overload отсутствует;
- новый structured key без `LogField` не компилируется;
- field без production producer удаляется либо явно регистрируется как
  отдельный seam, но не остаётся seed constant.

### Value contract

- `LONG` принимает integral Java values и до JSON нормализуется в `Long`;
- fractional number для `LONG` отвергается;
- `BOOLEAN` принимает только boolean;
- numeric-looking ID остаётся string;
- null field отсутствует, а не сериализуется как строка `"null"`;
- несовместимый value не получает silent `String.valueOf` fallback.

### MDC/event split

- ordinary SLF4J log внутри run scope получает string correlation fields;
- `MdcScope` отвергает numeric/boolean `LogField`;
- `MdcScope.hide(...)` восстанавливает скрытый key и не записывает новое
  значение;
- event-local key побеждает одноимённый MDC key;
- итоговый JSON содержит key ровно один раз;
- pre-existing MDC восстанавливается после log call и exception;
- два parallel run не смешивают MDC;
- async appender сохраняет typed pairs после закрытия producer scope.

### Encoder/wire

Реальный Boot `StructuredLogEncoder` парсится как JSON tree, где:

- `event.duration` — integral number;
- `ioc.rows` — integral number;
- все diagnostic counts — integral numbers;
- `ioc.sync.shed_to_reconcile` — boolean;
- run/source/event IDs — strings;
- `service.name` и `event.dataset` сохранены;
- exception fields присутствуют;
- `ecs.version` соответствует Boot formatter contract;
- member order не проверяется.

### Regression

- severity→log.level mapping OBS-1 не меняется;
- redaction diagnostic/TRACE не меняется;
- disabled gate не вызывает formatter/render;
- logging failure isolation diagnostic sink сохраняется;
- daemon rolling file и human-readable oneshot console сохраняются;
- startup strict `ioc.*` preflight не затрагивается новыми `logging.*`
  properties.

## Нарезка реализации

### 1. Spring Boot 3.4 baseline

**Commit:** `BUILD: upgrade Spring Boot to 3.4.13`

- обновить parent baseline;
- не менять encoder/LogEvent behavior в том же commit;
- выполнить dependency convergence review, startup/config binding tests,
  actuator, Spring Integration daemon tests и полный reactor verify;
- зафиксировать только необходимые migration adjustments.

**Gate:** `./mvnw verify`, oneshot help/startup, daemon context + actuator,
strict config regression.

### 2. Исполнимая logging schema

**Commit:** `REFACTOR: classify structured log field types`

- добавить `LogValueType` и metadata во все `LogField`;
- добавить JDBC fields, убрать arbitrary string field API;
- удалить/подключить unused constants;
- расширить structural tests и generated catalog колонкой JSON type;
- пока оставить текущий MDC transport, чтобы commit был behavior-preserving.

**Модули:** `platform-observability`, `adapter-store-jdbc`, `docs/`.

**Gate:** taxonomy/doc-sync + affected adapter tests.

### 3. Typed event transport и Boot ECS formatter

**Commit:** `FEATURE: emit typed ECS structured log fields`

- перевести `LogEvent` на SLF4J `LoggingEventBuilder.addKeyValue`;
- сделать `MdcScope` string-only и мигрировать numeric scope fields;
- реализовать collision contract;
- заменить Elastic encoder на Boot `StructuredLogEncoder`;
- удалить `logback-ecs-encoder` dependency/version property;
- сохранить rolling/service/dataset config;
- переписать log capture tests с MDC event fields на key/value pairs;
- добавить real JSON type matrix и async/concurrency regression;
- обновить `LOGGING.md`, taxonomy, module READMEs и закрыть OBS-D3.

**Модули:** `platform-observability`, `platform-diagnostics-logging`,
затронутые adapters, `bootstrap`, `docs/`.

**Gate:** targeted tests, полный `./mvnw verify`, `git diff --check`.

## Stand verification / Definition of Done

После сборки RC daemon разворачивается на тестовом стенде:

1. clean rolling file создаётся Boot ECS formatter-ом;
2. extraction с diagnostics публикует numeric total/counts;
3. sync admission публикует boolean `shed_to_reconcile`;
4. stage completion публикует numeric `event.duration`;
5. Python smoke проверяет `isinstance(value, int|bool)`, а IDs — `str`;
6. обычный framework/adapter log внутри run scope содержит correlation MDC;
7. parallel ingest двух файлов не смешивает run ids;
8. redaction и terminal completion остаются прежними;
9. saved queries/scripts стенда не предполагают строковые counts.

Если доступен disposable Elasticsearch, дополнительный smoke создаёт mapping с
`coerce=false` и индексирует representative event. Elasticsearch dependency,
container и timing gate не добавляются в обычный reactor только ради этого
необязательного stand check.

OBS-D3 считается закрытым, когда:

- full verify и stand smoke зелёные;
- production JSON содержит корректные scalar types;
- catalog публикует типы;
- arbitrary field/MDC numeric escape paths удалены;
- `logback-ecs-encoder` отсутствует в dependency tree;
- dev/module docs описывают dual-channel contract;
- OBS-5 зарегистрирован как будущий Elasticsearch activation seam, но не
  выдан за реализованный template.

## Следствия

Положительные:

- dashboards и aggregations получают числа/boolean без runtime conversion;
- тип поля имеет единственный источник истины;
- correlation остаётся доступной сторонним логам;
- logging backend можно менять через standard SLF4J event contract;
- новый field не может незаметно обойти generated taxonomy;
- будущий Elasticsearch template может генерироваться из той же metadata.

Отрицательные/операционные:

- Spring Boot minor upgrade расширяет regression surface;
- ECS JSON wire format меняется, включая `ecs.version`;
- существующий Elasticsearch keyword mapping потребует rollover/reindex;
- Python/scripts, ожидающие все custom values как strings, нужно обновить;
- numeric event properties больше не размазываются ambient MDC по каждому
  вложенному framework log;
- type mismatch становится fail-fast programming defect.

## Открытые точки расширения

1. **OBS-5:** generated Elasticsearch component template + installation/
   rollover/reindex lifecycle при первом supported ES consumer.
2. `event.severity` — только после согласования source-specific numeric scale.
3. `DOUBLE`/arrays/objects — только с первым реальным field contract.
4. Async/reactive context propagation — только при появлении реальной
   executor/reactive stage boundary.
5. Structured console output — отдельное операторское решение; OBS-D3
   сохраняет human-readable oneshot console.

## Источники

- [Spring Boot 3.4 — Structured Logging](https://docs.spring.io/spring-boot/3.4/reference/features/logging.html#features.logging.structured)
- [SLF4J 2 fluent logging API](https://www.slf4j.org/manual.html#fluent)
- [Elastic ECS event fields](https://www.elastic.co/docs/reference/ecs/ecs-event)
- [Elasticsearch numeric field types](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/number)
- [Elasticsearch `coerce`](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/coerce)
- [Elasticsearch component templates](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-cluster-put-component-template)
- [Elastic `EcsEncoder` source](https://github.com/elastic/ecs-logging-java/blob/main/logback-ecs-encoder/src/main/java/co/elastic/logging/logback/EcsEncoder.java)
- [Elastic `EcsJsonSerializer` source](https://github.com/elastic/ecs-logging-java/blob/main/ecs-logging-core/src/main/java/co/elastic/logging/EcsJsonSerializer.java)
