# Логирование и трассировка фонового сервиса

Operational logging для `oneshot` CLI и `daemon`/stream-режима.
Логи — это поток событий выполнения приложения: lifecycle, IO, вызовы сервисов,
записи артефактов, retry, latency, ошибки и технический контекст. Диагностика
конвейера может попадать в этот поток, но не является его центром.

> Статус: **реализовано**: dual-channel structured logging, generated typed
> taxonomy, stage/adapters events, Boot ECS JSON rolling file,
> resilient/redacting `LoggingDiagnosticSink` и gated structured per-item TRACE.
> Ambient string correlation передаётся через MDC; event-local strings,
> numbers и booleans — через SLF4J 2 key/value pairs. `event.dataset`
> статически равен `ioc-extractor`.

Remote sync использует ECS actions `sync_fetch_start|complete`,
`sync_publish_start|complete`, `sync_work_admission`, `sync_work_dispatch`,
`event_publish` и `event_dispatch` с логическими `ioc.source.id`,
`ioc.sync.target`, `ioc.sync.endpoint`, `ioc.sync.key`, `ioc.export.profile` и
counters. Успешные event publish/dispatch сигналы остаются `DEBUG`; сбои publish,
dispatch и keyed executor degradation пишутся на `WARN`/`ERROR`, чтобы fire-and-observe
контракт не делал ошибки невидимыми. Host/share/username/password не логируются;
transport exceptions проходят через общую error taxonomy. Последнее состояние операций
доступно в actuator `sync` health contributor — см. [sync.md](sync.md).

## Разделение моделей

| Модель | Назначение | Пример | Где живёт |
|---|---|---|---|
| Log event | операционное событие выполнения приложения | файл захвачен, sink записал CSV, retry начался | logging/observability |
| Diagnostic | результат/проблема обработки данных в pipeline | IOC пропущен, row mapping отклонён | diagnostics |
| Exception | сбой исполнения или нарушение контракта | Tika не прочитал файл, CSV не записан | слой возникновения + трансляция |
| Metric | числовое состояние/счётчик | очередь, duration, processed_total | future metrics adapter |

Правило: `Diagnostic` может быть вложен в log event как поля
`ioc.diagnostic.*`, но большинство log events не имеют диагностики. Обратное тоже
верно: диагностический результат может попасть не только в лог, но и в отчёт,
dead-letter sidecar или JSONL.

## ECS как базовая схема

Для структурированных логов принимаем подход Elastic Common Schema (ECS):

- стандартные поля — `@timestamp`, `log.level`, `message`, `service.*`,
  `event.*`, `error.*`, `process.*`, `trace.*`;
- проектная специфика — только в собственном namespace `ioc.*`;
- текстовые логи допустимы для локального `oneshot`, но machine-readable daemon
  logs должны быть ECS-compatible JSON.

Минимальная проектная таксономия полей и событий — в
[logging-taxonomy.md](LOGGING-TAXONOMY.md). Она фиксирует стартовое ядро и правила
расширения, а не пытается заранее описать все будущие events.

## Источники log events

| Источник | Какие события пишет | Как |
|---|---|---|
| Application bootstrap | старт/остановка, профиль, версия, конфиг | SLF4J |
| CLI adapter | запуск команды, source path, dry-run | `LogEvent`; run correlation — MDC |
| Ingest adapter | detect/claim/stabilize/move/retry/dead-letter | `LogEvent`; ambient context — MDC |
| Application pipeline | stage started/completed, counters, duration | `LogEvent` + typed key/value pairs |
| Domain services | возвращают pure decision outcomes | не знают SLF4J/ECS; TRACE формирует application |
| Out adapters | IO: CSV projection/export, JDBC storage, projection written | SLF4J |
| Diagnostics bridge | diagnostic result rendered as log event | `LoggingDiagnosticSink` |
| Export observer (bootstrap) | formation checkpoints/recovery, profile/slice/revision | `ExportObserver` → `LogEvent` |
| Export schedulers (bootstrap) | cadence failures, slice-retention sweep/blocks | `LogEvent` helper; ошибка tick не останавливает daemon |
| Event/sync observers (bootstrap) | control-event publish/dispatch и keyed executor degradation | `ControlEventObserver`, `KeyedSerialExecutorObserver` |

## Границы ответственности

| Компонент | Делает | Не делает |
|---|---|---|
| Domain service | применяет бизнес-правило, может вернуть diagnostic data | не знает про ECS, Logback, файлы, Spring MDC |
| Application pipeline | задаёт `run_id`, stage scope, counters, duration | не выбирает appender/rolling policy |
| Adapter | логирует инфраструктурные действия своей технологии | не реализует бизнес-классификацию IOC |
| `LoggingDiagnosticSink` | переводит `Diagnostic` в обычный log event | не является главным каналом логирования |
| `LoggingPipelineDecisionTracer` | пишет structured per-item decisions из готовых outcomes | не повторяет domain calls и не влияет на policy |
| Boot `StructuredLogEncoder` + `IocEcsStructuredLogEncoder` | форматирует SLF4J event в ECS JSON; bootstrap-обёртка добавляет static dataset в общий context-pair stream | не содержит бизнес-правил |

## Режимы вывода

### `oneshot`

CLI-режим оптимизирован для человека:

- console-first;
- уровень по умолчанию `INFO`;
- файл лога опционален;
- `DEBUG` включает решения стадий и счётчики;
- `TRACE` допускает per-indicator детали.

### `daemon`

Фоновый standalone-режим требует durable logging:

- console для container/systemd journal;
- rolling file для standalone/systemd;
- ECS JSON для машинного анализа;
- retention через rolling policy;
- уровень по умолчанию `INFO`; per-item `TRACE` — только по флагу/в коротком окне
  (объём логов на больших фидах);
- `var/logs/` не коммитится.

Рекомендуемая структура:

```text
var/
└── logs/
    ├── ioc-extractor.log       # text, optional for local troubleshooting
    ├── ioc-extractor.ecs.json  # ECS JSON lines
    └── diagnostics.jsonl       # optional later, if diagnostics split from app log
```

## Structured per-item TRACE

Per-item решения пишутся одним `event.action=pipeline_item_decision` и
различаются `ioc.decision.kind`: `REFANG`, `EXTRACTION`, `CLASSIFICATION`,
`ATTRIBUTION`, `DEDUPLICATION`, `ROUTING`. Эмиттер открывается только при
одновременном выполнении двух условий:

```text
ioc.observability.per-item-trace-enabled = true
AND logger TRACE enabled
```

Application проверяет gate до сборки decision DTO; adapter повторно
проверяет его до render/log call. Оба gate-off сценария дают ноль
renderer calls. TRACE читает только уже вычисленные domain/application outcomes:

- refang — applied rule + число replacements; per-replacement spans не материализуются;
- extraction — pattern, half-open span и kept/dropped status;
- classification — features, selected rule/predicates и mask result; только NETWORK;
- attribution/dedup/routing — selected marker, dedup key и artifact outcome.

`ioc.item.identity` — short SHA-256 + type/span. Raw value допускается только
в этом явно открытом TRACE; URL query и credentials маскируются на любом
уровне. INFO/WARN/ERROR/FATAL diagnostic renderer вместо raw
`indicator|item|value` пишет short hash.

## Spring Boot и ECS encoder

Проект использует Spring Boot `4.0.7` и встроенный
`org.springframework.boot.logging.logback.StructuredLogEncoder`. ECS context
pairs сериализуются как nested JSON. Daemon rolling
appender остаётся явно настроенным в `logback-spring.xml`, а небольшой
bootstrap-адаптер сохраняет статический dataset в том же context-pair stream,
что и динамические `event.*`:

```xml
<encoder class="com.iocextractor.bootstrap.IocEcsStructuredLogEncoder">
    <format>ecs</format>
    <charset>UTF-8</charset>
    <eventDataset>${SERVICE_NAME}</eventDataset>
</encoder>
```

`service.name` и `service.version` задаются через `logging.structured.*` в
`application.yml`; `event.dataset` берётся из того же `service.name` через
`springProperty` в `logback-spring.xml`. `IocEcsStructuredLogEncoder` временно
добавляет dataset к копии event-local pairs перед делегированием Boot encoder-у
и восстанавливает исходный event после encode. Это обходит конфликт двух
отдельных nested-объектов `event`, который возникает при сочетании
`logging.structured.json.add.event.dataset` и динамических `event.*`.
Дополнительная зависимость `logback-ecs-encoder` отсутствует. Boot formatter
сериализует MDC как строки, а `ILoggingEvent.getKeyValuePairs()` — с исходными
scalar types; текущий output публикует `ecs.version=8.11`.

JSON wire format является операторским контрактом. Переход со старого Elastic
encoder меняет набор/порядок служебных полей и ECS version. Если Elasticsearch
уже создал `keyword` mapping для прежних строковых counters, producer fix не
исправит существующий индекс: нужен rollover/reindex. Генерация и установка
component template зарегистрированы отдельным seam OBS-5.

## MDC, event-local fields и correlation

Structured event использует два стандартных канала SLF4J:

- MDC — ambient context, который должен попасть и в сторонние framework logs;
  он принимает только `LogField` типа `STRING`;
- SLF4J key/value pairs — поля конкретной occurrence, включая `LONG` и
  `BOOLEAN`; `LogEvent` проверяет и нормализует их по `LogField`.

MDC — **производное от `Envelope.meta`** ([pipeline.md](pipeline.md)): источник
истины по корреляции — конверт, проходящий через стадии. `ioc.run.id`, source и
mode живут в run scope; вложенный stage scope добавляет только `ioc.stage`.
Оба снимаются в `close()`. Terminal run diagnostics поэтому сохраняют
корреляцию, но не получают ложную последнюю стадию.

Каноничная корреляция приложения — project fields. ECS `trace.id`/`transaction.id`
используем только когда есть совместимый tracing id (W3C/APM) или внешний trace.
Это не мешает позже подключить APM, но не заставляет подменять ECS trace обычным
run id.

| Ключ | ECS / custom | Когда задаётся |
|---|---|---|
| `ioc.run.id` | custom | один pipeline-прогон (run) — каноничный correlation id |
| `ioc.source.id` | custom | обработка одного source |
| `trace.id` | ECS | если есть внешний/W3C-compatible trace id |
| `transaction.id` | ECS | если есть внешний/APM-compatible transaction id |
| `ioc.mode` | custom | `oneshot` / `daemon` |
| `ioc.stage` | custom | вокруг стадии |
| `ioc.source.path` | custom | путь источника |
| `ioc.source.content_hash` | custom | после стабилизации файла |
| `ioc.artifact.name` | custom | запись sink/projection |

`event.dataset` задаётся статически (`ioc-extractor`) bootstrap-энкодером;
per-event детализация идёт через `event.action`,
`ioc.stage`, `ioc.artifact.name`, `ioc.diagnostic.*` и другие catalog fields.
Если event-local key совпал с ambient MDC key, event-local значение побеждает:
`LogEvent` временно скрывает MDC member на время logger call и затем
восстанавливает его. В JSON поэтому нет duplicate keys и typed value не
деградирует до строки.

Helper `MdcScope`, наполняемый из `Envelope.meta` (реализовано, этап 8):

```java
try (MdcScope ignored = MdcScope.open()
        .put(LogField.IOC_RUN_ID, runId)
        .put(LogField.IOC_STAGE, "extract")) {
    // stage work
}
```

`MdcScope` обязан очищать добавленные ключи в `close()`, иначе daemon-поток
получит протечки контекста между источниками. Числовой/boolean field положить в
MDC невозможно: это fail-fast programming defect.

## Уровни логирования

| Level | Что допускается |
|---|---|
| ERROR | failed source, невозможность записать артефакт, fatal exception |
| WARN | retry, восстановимая деградация, validation warning |
| INFO | app start/stop, source processed, artifact written, итоговые counters |
| DEBUG | stage timings, выбранные правила, IO paths, dedup decisions |
| TRACE | raw matches, per-indicator features, промежуточные значения |

`INFO` должен отвечать на вопрос “жив ли сервис и что он обработал”.
`DEBUG` должен позволять восстановить решение. `TRACE` включается кратковременно.

## Форматы

Текстовый локальный формат:

```text
2026-06-21T12:00:01.123 INFO  event.action=artifact_project event.outcome=success ioc.run.id=01J... ioc.artifact.name=masks ioc.rows=58
```

ECS JSON:

```json
{
  "@timestamp": "2026-06-21T04:00:01.123Z",
  "ecs": {"version": "8.11"},
  "log": {"level": "INFO"},
  "message": "artifact written",
  "service": {"name": "ioc-extractor", "version": "0.1.0"},
  "event": {
    "dataset": "ioc-extractor",
    "action": "artifact_project",
    "category": ["file"],
    "type": ["creation"],
    "outcome": "success"
  },
  "ioc": {
    "run": {"id": "01J..."},
    "stage": "sink",
    "artifact": {"name": "masks"},
    "rows": 58
  }
}
```

Если log event связан с диагностикой, добавляются поля:

```json
{
  "event": {"action": "indicator_skip", "outcome": "failure"},
  "ioc": {
    "diagnostic": {
      "code": "EXTRACTION.INDICATOR_SKIPPED",
      "category": "EXTRACTION",
      "severity": "WARN"
    }
  }
}
```

## Хранение и retention

Правила:

- `var/logs/` в `.gitignore`;
- rolling file с `max-file-size`, `max-history`, `total-size-cap`;
- console appender сохраняется для journal/container logs;
- retention логов не связан с retention каталогов инжеста;
- JSONL diagnostics можно добавить позже, если эксплуатационно нужно отделить
  data-processing report от общего application log.

## Безопасность данных

Логи могут содержать IOC, пути и source labels:

- `INFO` не пишет полный список IOC;
- raw IOC разрешён только на `DEBUG`/явно gated per-item `TRACE`;
  INFO/WARN/ERROR/FATAL renderer заменяет raw value short hash;
- URL query и credentials маскируются независимо от log level;
- исключения логируются с context, но без дампа всего входного документа;
- operational logs и JSONL не коммитятся.

`ecs.version` в примере — output Boot formatter-а; приложение не проставляет
это поле вручную.

## Исполненный контракт

- Есть string-only `MdcScope`, покрытый тестами очистки, nested restore и
  запрета numeric/boolean fields.
- Есть typed `LogField` schema (`STRING|LONG|BOOLEAN`) и generated
  `LOGGING-CATALOG.md`.
- `LogEvent` использует SLF4J key/value pairs; arbitrary string keys удалены,
  collision contract и async hand-off покрыты тестами.
- Driving boundary задаёт `ioc.run.id`; application переносит его через
  `Envelope.meta`, а observer открывает run/stage scopes.
- Основные стадии пишут operational log events со счётчиками/duration.
- CLI и daemon terminal events публикуют `ioc.completion.status`, total,
  suppressed и отдельные severity counters; duplicate skip остаётся отдельным
  `source_ingest` без синтетического completion.
- `Diagnostic` может быть отрендерен в log event через `LoggingDiagnosticSink`,
  но logging не зависит от diagnostics.
- `daemon`-профиль пишет nested ECS JSON rolling file через Boot
  `StructuredLogEncoder` и bootstrap dataset-адаптер; duration/counts — JSON
  numbers, flags — booleans.
- `oneshot` остаётся удобным в console.
- Тесты проверяют отсутствие MDC-протечек, typed key/value pairs, async snapshot
  и representative ECS JSON file.
- Severity mapping запинен для всех шести уровней; sink failure изолирован
  non-throwing decorator-ом.
- Structured TRACE тестирует оба закрытых gate, общий query/credentials
  sanitizer и отсутствие повторной classification.

## Референсы

- Elastic Common Schema: <https://www.elastic.co/docs/reference/ecs>
- SLF4J fluent logging: <https://www.slf4j.org/manual.html#fluent>
- Spring Boot structured logging: <https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured>
