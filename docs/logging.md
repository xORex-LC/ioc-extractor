# Логирование и трассировка фонового сервиса

Operational logging для `oneshot` CLI и `daemon`/stream-режима.
Логи — это поток событий выполнения приложения: lifecycle, IO, вызовы сервисов,
записи артефактов, retry, latency, ошибки и технический контекст. Диагностика
конвейера может попадать в этот поток, но не является его центром.

> Статус: **реализовано базовое ядро этапа 8**: MDC scope, code taxonomy,
> LogEvent helper, stage/adapters events, ECS JSON rolling file для
> `daemon`-профиля и `LoggingDiagnosticSink`. `event.dataset` на этом этапе
> задаётся encoder-ом статически как `ioc-extractor`; детализация событий идёт
> через `event.action` и `ioc.*`. Дальнейшее расширение событий добавляется
> рядом с новыми producer-ами.

## Разделение моделей

| Модель | Назначение | Пример | Где живёт |
|---|---|---|---|
| Log event | операционное событие выполнения приложения | файл захвачен, sink записал CSV, retry начался | logging/observability |
| Diagnostic | результат/проблема обработки данных в pipeline | IOC пропущен, классификация неоднозначна | diagnostics |
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
[logging-taxonomy.md](logging-taxonomy.md). Она фиксирует стартовое ядро и правила
расширения, а не пытается заранее описать все будущие events.

## Источники log events

| Источник | Какие события пишет | Как |
|---|---|---|
| Application bootstrap | старт/остановка, профиль, версия, конфиг | SLF4J |
| CLI adapter | запуск команды, source path, dry-run | SLF4J + MDC |
| Ingest adapter | detect/claim/stabilize/move/retry/dead-letter | SLF4J + MDC |
| Application pipeline | stage started/completed, counters, duration | SLF4J / LogEvent helper |
| Domain services | редкие DEBUG/TRACE решения без инфраструктурных деталей | SLF4J или diagnostic result |
| Out adapters | IO: read/write CSV, lookup loaded, partition written | SLF4J |
| Diagnostics bridge | diagnostic result rendered as log event | `LoggingDiagnosticSink` |

## Границы ответственности

| Компонент | Делает | Не делает |
|---|---|---|
| Domain service | применяет бизнес-правило, может вернуть diagnostic data | не знает про ECS, Logback, файлы, Spring MDC |
| Application pipeline | задаёт `run_id`, stage scope, counters, duration | не выбирает appender/rolling policy |
| Adapter | логирует инфраструктурные действия своей технологии | не реализует бизнес-классификацию IOC |
| `LoggingDiagnosticSink` | переводит `Diagnostic` в обычный log event | не является главным каналом логирования |
| Logback / ECS encoder | форматирует и пишет события | не содержит бизнес-правил |

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

## Spring Boot и ECS encoder

Текущий проект использует Spring Boot `3.3.5`. Для него практичный вариант —
официальный Elastic `ecs-logging-java`:

```xml
<dependency>
    <groupId>co.elastic.logging</groupId>
    <artifactId>logback-ecs-encoder</artifactId>
    <version>${ecs-logging-java.version}</version>
</dependency>
```

И `logback-spring.xml` с `co.elastic.logging.logback.EcsEncoder` или готовыми
Spring Boot include-файлами Elastic:

```xml
<include resource="org/springframework/boot/logging/logback/defaults.xml"/>
<include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
<include resource="org/springframework/boot/logging/logback/file-appender.xml"/>
<include resource="co/elastic/logging/logback/boot/ecs-console-appender.xml"/>
<include resource="co/elastic/logging/logback/boot/ecs-file-appender.xml"/>
```

После будущего перехода на Spring Boot `3.4+` можно рассмотреть встроенный
structured logging без дополнительной Elastic-зависимости:

```yaml
logging:
  structured:
    format:
      console: ecs
      file: ecs
```

Решение для текущего этапа: **использовать `ecs-logging-java` с Logback**, потому
что проект уже на Spring Boot 3.3.x и Logback является стандартным backend.
При upgrade до Boot 3.4+ сравнить встроенный ECS formatter и Elastic encoder по
поддержке MDC/custom fields, rolling setup и совместимости с нужным ECS output.
Версию ECS (`ecs.version`) проставляет encoder — в коде её не хардкодим.

## MDC / correlation

MDC — **производное от `Envelope.meta`** ([pipeline.md](pipeline.md)): источник
истины по корреляции — конверт, проходящий через стадии. На входе в стадию/адаптер
ключи заполняются из меты конверта и снимаются в `close()`. MDC лишь делает
корреляцию доступной логгеру и потокобезопасной в daemon-пуле — это не отдельный
«контекст» и не дубль состояния.

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
| `ioc.artifact.name` | custom | запись sink/aggregation |
| `ioc.partition.path` | custom | stream partition |

`event.dataset` — ECS-поле encoder-а. В официальном `logback-ecs-encoder` оно
сериализуется до MDC, а одноимённый MDC-key фильтруется как reserved ECS key.
Поэтому seed-реализация задаёт его статически (`ioc-extractor`) в
`logback-spring.xml`; per-event детализация идёт через `event.action`,
`ioc.stage`, `ioc.artifact.name`, `ioc.diagnostic.*` и другие поля. Per-item поля
(`ioc.indicator.*`, `ioc.dedup.key`) — только в коротком scope и преимущественно
на `DEBUG`/`TRACE`.

Helper `MdcScope`, наполняемый из `Envelope.meta` (реализовано, этап 8):

```java
try (MdcScope ignored = MdcScope.open()
        .put(LogField.IOC_RUN_ID, runId)
        .put(LogField.IOC_STAGE, "extract")) {
    // stage work
}
```

`MdcScope` обязан очищать добавленные ключи в `close()`, иначе daemon-поток
получит протечки контекста между источниками.

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
2026-06-21T12:00:01.123 INFO  event.action=artifact_write event.outcome=success ioc.run.id=01J... ioc.artifact.name=masks ioc.rows=58
```

ECS JSON:

```json
{
  "@timestamp": "2026-06-21T04:00:01.123Z",
  "ecs.version": "...",
  "log.level": "INFO",
  "message": "artifact written",
  "service.name": "ioc-extractor",
  "service.version": "0.1.0",
  "event.dataset": "ioc-extractor",
  "event.action": "artifact_write",
  "event.category": ["file"],
  "event.type": ["creation"],
  "event.outcome": "success",
  "ioc.run.id": "01J...",
  "ioc.stage": "sink",
  "ioc.artifact.name": "masks",
  "ioc.rows": 58
}
```

Если log event связан с диагностикой, добавляются поля:

```json
{
  "event.action": "indicator_skip",
  "event.outcome": "failure",
  "ioc.diagnostic.code": "EXTRACTION.INDICATOR_SKIPPED",
  "ioc.diagnostic.category": "EXTRACTION",
  "ioc.diagnostic.severity": "WARN"
}
```

## Хранение и retention

Правила:

- `var/logs/` в `.gitignore`;
- rolling file с `max-file-size`, `max-history`, `total-size-cap`;
- console appender сохраняется для journal/container logs;
- retention логов не связан с retention партиций инжеста;
- JSONL diagnostics можно добавить позже, если эксплуатационно нужно отделить
  data-processing report от общего application log.

## Безопасность данных

Логи могут содержать IOC, пути и source labels:

- `INFO` не пишет полный список IOC;
- значения IOC — только `DEBUG`/`TRACE` и коротким scope;
- токены/query лучше маскировать или писать short hash, если они не нужны для
  расследования;
- исключения логируются с context, но без дампа всего входного документа;
- operational logs и JSONL не коммитятся.

`ecs.version` в примере — output encoder'а; приложение не проставляет это поле
вручную.

## Definition of Done для этапа 8

- Есть `MdcScope`, покрытый тестом на очистку контекста.
- Есть минимальные logging constants для стартовых `event.action` и `ioc.*` fields.
- `IocExtractionService` задаёт `ioc.run.id` (run) и stage scopes из `Envelope.meta`.
- Основные стадии пишут operational log events со счётчиками/duration.
- `Diagnostic` может быть отрендерен в log event через `LoggingDiagnosticSink`,
  но logging не зависит от diagnostics.
- `daemon`-профиль пишет ECS JSON rolling file.
- `oneshot` остаётся удобным в console.
- Тесты проверяют отсутствие MDC-протечек и корректный mapping ключевых событий —
  изолированно (in-memory appender / `OutputCapture`, без реальных файлов).

## Референсы

- Elastic Common Schema: <https://www.elastic.co/docs/reference/ecs>
- ECS Logging Java: <https://www.elastic.co/docs/reference/ecs/logging/java/setup>
- Spring Boot structured logging: <https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured>
