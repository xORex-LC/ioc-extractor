# План реализации этапа 8: logging / observability

> Примечание о нумерации: запрос сформулирован как «этап 7 - logging и
> observability», но опубликованная [roadmap.md](../roadmap.md) сейчас фиксирует
> порядок **6 diagnostics → 7 pipeline (`Envelope`) → 8 observability/logging**.
> Этот план назван `0008`, чтобы не расходиться с источником истины. Если порядок
> будет изменён, сначала синхронно обновляем roadmap и имя файла.

## Цель

Реализовать operational logging и observability-контур из
[logging.md](../logging.md), [logging-taxonomy.md](../logging-taxonomy.md) и
[0007-logging-observability.md](../notes/0007-logging-observability.md):
структурированные ECS-compatible log events, безопасный MDC scope, базовую
таксономию `event.*`/`ioc.*`, stage/adapters log events, daemon rolling ECS-file и
bridge `Diagnostic` → log event.

Цель этапа — не «добавить больше строк логов», а ввести стабильный контракт
наблюдаемости, пригодный для фонового standalone-режима и дальнейшего stream
ingestion.

## Предусловия

Обязательные:

- этап 6 diagnostics реализован: есть `Diagnostic`, `DiagnosticSink`, catalog,
  renderer и проверяемая карта кодов;
- этап 7 pipeline реализован: есть `Envelope.meta` как источник истины для
  `ioc.run.id`, `ioc.source.id`, `ioc.stage` и других correlation fields.

Если этап observability запускается до pipeline/`Envelope`, допустимо сделать
только подготовительную часть:

- `observability` package;
- `MdcScope`;
- logging taxonomy constants;
- `logback-spring.xml`;
- тесты отсутствия MDC leaks;
- без wiring stage events в `IocExtractionService` и без утверждения, что
  корреляция уже берётся из `Envelope.meta`.

## Границы этапа

Входит в этап:

- зависимость `ecs-logging-java` / `logback-ecs-encoder` для Spring Boot 3.3.x;
- `logback-spring.xml` с console и daemon ECS rolling file;
- `MdcScope`, который ставит и восстанавливает только свои MDC keys;
- whitelist/const для стартовых `event.action`, `event.outcome`, `ioc.*` fields;
- helper для scoped log events (`event.action`, `event.outcome`,
  `event.duration`);
- stage log events на application pipeline boundary;
- IO log events в adapters (`lookup_load`, `artifact_write`, source read);
- `LoggingDiagnosticSink` как bridge из diagnostics в operational log stream;
- tests: in-memory appender / `OutputCapture`, без реальных project log files;
- `.gitignore` для `var/logs/`.

Не входит в этап:

- полноценные metrics/OpenTelemetry/APM;
- отправка логов во внешний collector;
- daemon ingestion lifecycle, ledger, retry/dead-letter storage;
- per-indicator TRACE по умолчанию;
- замена diagnostics report на логи;
- собственная JSON-схема вне ECS;
- перенос проекта на Spring Boot 3.4+ ради built-in structured logging.

## Карта ответственности

| Уровень | Обязан делать | Запрещено делать |
|---|---|---|
| `observability` core | хранить logging field/action constants, `MdcScope`, event helper | знать о Tika/CSV/picocli деталях, бизнес-классификации IOC |
| application pipeline | создавать run/stage scopes из `Envelope.meta`, писать stage counters/duration | выбирать appenders/rolling policy, генерировать ECS JSON вручную |
| adapters | логировать свои IO-события и failure context | принимать бизнес-решения или формировать diagnostics вместо producer-кода |
| diagnostics logging bridge | маппить `Diagnostic` в log event fields | становиться главным каналом diagnostics или тянуть logging в `diagnostics` core |
| Logback/ECS encoder | форматировать и писать события | содержать бизнес-правила, hardcode `ecs.version` |

## Целевая структура файлов

В одномодульном проекте целевые `platform-*` модули отражаются пакетами. При
многомодульности они переедут в `platform-observability` и
`platform-diagnostics-logging`.

```text
pom.xml
src/main/resources/
├── logback-spring.xml
└── application.yml

src/main/java/com/iocextractor/observability/
├── README.md
├── package-info.java
├── EventAction.java
├── EventOutcome.java
├── LogField.java
├── MdcScope.java
└── ObservabilityMode.java

src/main/java/com/iocextractor/observability/logging/
├── README.md
├── package-info.java
├── LogEvent.java
├── LogEvents.java
└── LoggingPipelineObserver.java

src/main/java/com/iocextractor/observability/diagnostics/
├── README.md
├── package-info.java
└── LoggingDiagnosticSink.java

src/test/java/com/iocextractor/observability/
├── MdcScopeTest.java
├── LoggingTaxonomyTest.java
└── LogbackConfigurationTest.java

src/test/java/com/iocextractor/observability/diagnostics/
└── LoggingDiagnosticSinkTest.java
```

Дополнительно:

- `docs/logging-taxonomy.md` обновляется только если появляются реальные новые
  fields/actions;
- `docs/plan/README.md` получает строку этого плана;
- `.gitignore` получает `var/logs/` и, при необходимости, `*.ecs.json`.

## Контракты типов

### `MdcScope`

Назначение: временно добавить MDC keys и гарантированно восстановить прежнее
состояние в `close()`.

Контракт:

```java
try (var ignored = MdcScope.open()
        .put(LogField.IOC_RUN_ID, runId)
        .put(LogField.IOC_STAGE, stage)) {
    logger.info("stage started");
}
```

Правила:

- `MdcScope` работает только через try-with-resources;
- `close()` восстанавливает прежнее значение key или удаляет key, если его не
  было;
- nested scopes поддержаны;
- `null` value удаляет key или запрещается явно — решение фиксируется тестом;
- scope не переносит MDC между потоками; для daemon thread pool context
  прокидывается явно на boundary.

### `LogField`

`LogField` фиксирует только stable field names:

- ECS-ish keys: `event.action`, `event.outcome`, `event.duration`;
- project keys: `ioc.run.id`, `ioc.source.id`, `ioc.mode`, `ioc.stage`,
  `ioc.source.path`, `ioc.source.content_hash`, `ioc.artifact.name`, `ioc.rows`;
- diagnostic keys: `ioc.diagnostic.code`, `ioc.diagnostic.category`,
  `ioc.diagnostic.severity`.

Не добавлять per-item fields (`ioc.indicator.*`, `ioc.dedup.key`) на старте.
Они вводятся отдельным решением и только для `DEBUG`/`TRACE`.

### `EventAction` / `EventOutcome`

Стартовый набор соответствует [logging-taxonomy.md](../logging-taxonomy.md):

- actions: `app_start`, `app_stop`, `command_start`, `command_complete`,
  `stage_start`, `stage_complete`, `lookup_load`, `source_read`,
  `artifact_write`, `diagnostic_emit`;
- outcomes: `success`, `failure`, `unknown`.

`event.dataset` задаётся `logback-ecs-encoder` статически как `ioc-extractor`.
Per-event dataset через MDC не использовать: официальный encoder фильтрует
reserved ECS keys из MDC.

Новый action добавляется вместе с первым producer-ом события и тестом
стабильности taxonomy.

### `LogEvent` / `LogEvents`

Минимальный helper, чтобы producer-код не дублировал MDC boilerplate:

```java
LogEvents.info(logger)
        .action(EventAction.STAGE_COMPLETE)
        .outcome(EventOutcome.SUCCESS)
        .durationNanos(duration)
        .field(LogField.IOC_ROWS, rows)
        .message("stage completed")
        .log();
```

Ограничения:

- helper только добавляет MDC fields вокруг одного log call;
- не хранит глобальное состояние;
- не заменяет SLF4J как facade;
- не формирует JSON вручную.

Если helper окажется избыточным, допустима более простая реализация:
`try (MdcScope event = MdcScope.open()...) { logger.info(...) }`.

### `LoggingDiagnosticSink`

Bridge живёт в `observability.diagnostics`, а не в `diagnostics`.

Маппинг:

| Diagnostic | Log field |
|---|---|
| `code.id()` | `ioc.diagnostic.code` |
| `category()` | `ioc.diagnostic.category` |
| `severity()` | `ioc.diagnostic.severity` |
| `cause()` | `error.*` через Logback/ECS throwable handling |

Правила:

- `LoggingDiagnosticSink` зависит на diagnostics + observability;
- `diagnostics` core не зависит на SLF4J/Logback;
- severity → log level:
  - `FATAL`, `ERROR` → `ERROR`;
  - `WARN` → `WARN`;
  - `INFO` → `INFO`;
  - `DEBUG` → `DEBUG`;
  - `TRACE` → `TRACE`;
- message берётся через `DiagnosticRenderer`;
- event action bridge не изобретает бизнес-событие, а использует нейтральный
  action вроде `diagnostic_emit`, если отдельного producer action нет.

## Конфигурация Logback / ECS

### Maven

Добавить property и dependency:

```xml
<ecs-logging-java.version><!-- выбрать совместимую актуальную версию --></ecs-logging-java.version>

<dependency>
    <groupId>co.elastic.logging</groupId>
    <artifactId>logback-ecs-encoder</artifactId>
    <version>${ecs-logging-java.version}</version>
</dependency>
```

Точную версию проверяем по официальной Elastic/Maven Central информации в момент
реализации. `ecs.version` приложение не задаёт вручную.

### `logback-spring.xml`

Профили:

| Профиль | Вывод | Назначение |
|---|---|---|
| default / oneshot | console text | удобный CLI |
| daemon | console + rolling ECS JSON file | standalone/systemd/container |
| test | console/off или in-memory в тестах | без project files |

Daemon file:

```text
var/logs/ioc-extractor.ecs.json
```

Rolling policy:

- `maxFileSize`: стартово 10MB;
- `maxHistory`: стартово 14 дней;
- `totalSizeCap`: стартово 512MB;
- параметры можно вынести в `application.yml`/system properties.

### `application.yml`

Добавить только безопасные defaults:

```yaml
logging:
  level:
    root: INFO
    com.iocextractor: INFO
  file:
    path: var/logs

ioc:
  observability:
    mode: oneshot
    per-item-trace-enabled: false
```

Не размазывать log taxonomy по YAML. Actions/fields — code constants.

## Интеграционные точки

### Bootstrap / CLI

События:

- `app_start`;
- `app_stop`;
- `command_start`;
- `command_complete`.

Контекст:

- `ioc.mode`;
- `ioc.source.path`;
- `event.outcome`;
- error throwable при failure.

### Pipeline

После этапа 7:

- `ioc.run.id` и `ioc.source.id` берутся из `Envelope.meta`;
- stage boundary ставит `ioc.stage`;
- `stage_start` и `stage_complete` пишутся на `DEBUG` или `INFO` для крупных
  стадий;
- `event.duration` в наносекундах;
- counters: extracted/retained/written rows только агрегированно.

На `INFO` не писать полный список IOC.

### Adapters

Стартовые события:

- source read success/failure;
- lookup load;
- artifact write.

Правило: adapter логирует IO своей технологии, но не принимает доменные решения.

### Diagnostics bridge

`LoggingDiagnosticSink` подключается через composition root только как один из
потребителей `DiagnosticSink`. На этапе 8 можно оставить `CollectingDiagnosticSink`
для тестов/report и добавить bridge рядом, не заменяя diagnostics модель.

## Тестовый и CI-контур

Обязательные проверки:

- `MdcScopeTest`: basic put/close, restore previous value, nested scopes, close
  idempotency или явный fail-fast contract;
- `LoggingTaxonomyTest`: constants не пустые, `event.action` уникальны, fields из
  whitelist не меняются случайно;
- `LoggingDiagnosticSinkTest`: in-memory Logback appender проверяет level,
  message, MDC diagnostic fields, throwable;
- `LogbackConfigurationTest`: конфигурация парсится, daemon appender не пишет в
  project files в тесте; при file-тесте использовать `@TempDir`;
- `ArchitectureTest`: `diagnostics..` всё ещё не зависит от SLF4J/Logback;
  `LoggingDiagnosticSink` не находится в `diagnostics..`; observability не
  зависит от domain business packages.

CI:

```bash
./mvnw test
./mvnw -B -ntp verify
git diff --check
```

Запрещено в тестах:

- писать в `var/logs/` проекта;
- проверять содержимое реальных production logs;
- зависеть от текущей даты/часового пояса;
- включать TRACE per-item события по умолчанию.

## Порядок реализации

1. Обновить `.gitignore`: `var/logs/`.
2. Добавить `ecs-logging-java` dependency и `logback-spring.xml`.
3. Создать `observability` root package + README/package-info.
4. Реализовать `LogField`, `EventAction`, `EventOutcome`.
5. Реализовать `MdcScope` с тестами nested/restore/no leak.
6. Реализовать минимальный event helper (`LogEvents`) или зафиксировать прямой
   паттерн `MdcScope + logger`.
7. Добавить `LoggingDiagnosticSink` в `observability.diagnostics`.
8. Добавить тесты bridge через in-memory appender.
9. Обновить `ArchitectureTest`:
   - снять запрет на существование `LoggingDiagnosticSink`;
   - запретить его размещение в `diagnostics..`;
   - запретить SLF4J/Logback в `diagnostics..`.
10. Подключить app/CLI log events.
11. После этапа 7 pipeline подключить stage scopes из `Envelope.meta`.
12. Подключить adapter events: lookup/source/sink.
13. Проверить, что `GoldenPipelineTest` не меняет output artifacts.
14. Запустить полный CI-контур.

## Критерии приёмки

- `./mvnw test` и `./mvnw -B -ntp verify` зелёные;
- `MdcScope` не оставляет keys после `close()`;
- correlation fields берутся из `Envelope.meta`, а не из отдельного глобального
  состояния;
- daemon-профиль пишет ECS JSON rolling file в `var/logs/`;
- default/oneshot остаётся console-friendly;
- `ecs.version` не хардкодится в приложении;
- diagnostics core не импортирует logging;
- `LoggingDiagnosticSink` маппит diagnostic fields в `ioc.diagnostic.*`;
- `INFO` не содержит per-indicator dumps;
- tests используют in-memory appender/`@TempDir`, не project log files.

## Риски и решения

| Риск | Решение |
|---|---|
| Observability начнёт дублировать pipeline context | MDC только производный от `Envelope.meta`; отдельного глобального context нет |
| Logging смешается с diagnostics | Bridge в `observability.diagnostics`; ArchUnit запрещает SLF4J/Logback в `diagnostics..` |
| ECS fields начнут задаваться вручную неверно | Использовать encoder; `ecs.version` не трогать; custom fields только под `ioc.*` |
| Логи станут слишком шумными | INFO только lifecycle/counters; per-item только DEBUG/TRACE и флагом |
| Тесты начнут писать реальные логи | In-memory appender и `@TempDir`; `var/logs/` не используется в CI |
| План обгонит pipeline stage | Stage wiring выполнять только после `Envelope.meta`; до этого только foundation |

## Проверка после реализации

```bash
./mvnw test
./mvnw -B -ntp verify
git diff --check
```

Опциональная ручная проверка daemon-профиля:

```bash
./mvnw -q -DskipTests package
java -Dspring.profiles.active=daemon -jar target/ioc-extractor-0.1.0-SNAPSHOT.jar extract --source source/ioc-source.htm --dry-run
```

Проверить, что появился `var/logs/ioc-extractor.ecs.json`, строки являются JSON,
а `var/logs/` не попал в git.
