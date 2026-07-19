# Защита архитектурных границ

Границы из [architecture.md](ARCHITECTURE.md) держатся не на доверии, а на
**автоматических проверках**. Цель — сделать нарушение правила (импорт фреймворка
в домен, обращение внутреннего слоя к внешнему, цикл зависимостей) failing-сборкой,
а не замечанием на ревью.

> Статус: **реализовано**. Базовые границы защищены Maven reactor, Maven
> Enforcer и ArchUnit. Расширенные проверки вроде Spring Modulith/canvas
> отложены как технический долг.

## Уровни защиты (от мягкого к жёсткому)

### 1. Тесты архитектуры — ArchUnit
Декларативные правила на зависимости пакетов, выполняются как обычные тесты:

- `domain` не зависит на `application`, `adapter`, `bootstrap` и на фреймворки
  (`org.springframework..`, `org.apache.tika..`, `picocli..`, `com.google.re2j..`,
  `org.apache.commons..`).
- `platform-etl` не зависит на IOC domain/application/adapters/bootstrap и
  остаётся framework-free.
- `application` зависит на `domain` и platform contracts, не на адаптеры/Spring.
- JDBC/`java.sql`/Hikari разрешены только adapters/bootstrap; Jackson manifest
  mapping — только `adapter-manifest-json-jackson`/bootstrap. Streaming slice
  writer отдельно защищён от JDBC и materialized canonical repository model.
- Внутри `ioc-domain` capability-пакеты держат DAG (`model/refang/extract/
  feature/classify/attribute`) через module-local ArchUnit.
- Onion/hexagonal-правило слоёв: доступ только «внутрь».
- Отсутствие циклов между пакетами.
- Контрактные типы в `application.port/**`: порты — интерфейсы, а
  `*Command`/`*Result` — явные data-contract исключения.

### 2. Границы модулей — Maven-реактор
После нарезки на модули направление зависимостей фиксируется в `pom.xml`: класс
из недоступного модуля просто не на classpath. Это самая сильная и дешёвая
гарантия для правила «domain framework-free». См. [modularization.md](MODULARIZATION.md).

### 3. maven-enforcer-plugin
- `banned-dependencies` — запрет нежелательных зависимостей в конкретных модулях
  (напр. Spring в `ioc-domain`).
- `dependencyConvergence` — отложено до отдельного build-hygiene шага.
- Циклы между Maven-модулями невозможны при успешной reactor-сборке.

### 4. JPMS `module-info.java` (опционально)
Сильная инкапсуляция: модуль экспортирует только порты и публичный API, прячет
внутренние пакеты адаптеров. Рассматриваем после стабилизации структуры.

### 5. Видимость и конвенции кода
- Package-private по умолчанию; публичное — осознанно (порты, value objects).
- Реализации адаптеров не «торчат» наружу мимо порта.
- Внедрение через конструктор; никакого доступа к composition root из ядра.

## Действующие executable-правила

Документ фиксирует намерение границ, но не дублирует полный ArchUnit source.
Исполняемая истина находится в тестах и POM; изменение правила обновляет эту
таблицу в том же change.

| Гарантия | Enforcement |
|---|---|
| Слои зависят только внутрь; package cycles запрещены | `bootstrap/ioc-app/.../ArchitectureTest` |
| Domain не импортирует frameworks, logging, adapters/application pipeline или ingest | `ArchitectureTest` + `core/ioc-domain/.../DomainBoundaryTest` + domain Enforcer |
| Application не импортирует adapters/bootstrap/Spring/logging | `ArchitectureTest` + Maven module graph |
| `platform-etl` не знает IOC/application/adapters/observability/frameworks | `ArchitectureTest.platform_etl_is_generic_and_framework_free` |
| `platform-events` остаётся framework-free, не зависит от keyed execution и не становится serializable broker contract | `ArchitectureTest.platform_events_is_framework_free_and_not_a_broker`, `control_events_are_not_serializable`, `keyed_execution_is_not_part_of_platform_events` |
| `platform-concurrency` не зависит от events/frameworks/business layers | `ArchitectureTest.platform_concurrency_is_framework_free_and_event_free` |
| JDBC/Hikari/SQLite и SMBJ types не протекают внутрь | storage/transport rules в `ArchitectureTest` + application Enforcer |
| Jackson manifest mapping остаётся в manifest/CLI/bootstrap boundaries | `ArchitectureTest.jackson_manifest_mapping_stays_in_its_adapter` |
| Adapters не владеют конкретными pipeline stages; stages не владеют порядком pipeline | pipeline rules в `ArchitectureTest` и `PipelineModelArchitectureTest` |
| Application port contracts — interfaces; command/result records являются явным исключением | `ArchitectureTest.ports_are_interfaces` |
| Внутридоменный capability DAG сохраняется | `core/ioc-domain/.../DomainBoundaryTest` |

ArchUnit выполняется как обычный JUnit test над production-классами и красит
`verify` при нарушении. Module-local тесты дополняют общий cross-module contour.

## Действующие правила Maven Enforcer

Parent reactor применяет ко всем модулям:

- `requireJavaVersion` (`[21,)`);
- `requireMavenVersion` (`[3.9,)`);
- `banDuplicatePomDependencyVersions`.

`ioc-domain` дополнительно запрещает Spring, Tika, Commons CSV/IO, Guava,
RE2/J, picocli, HikariCP и sqlite-jdbc. `ioc-application` запрещает JDBC,
Spring transactions, HikariCP и sqlite-jdbc. Точные coordinates принадлежат
соответствующим POM, чтобы документация не стала второй исполняемой allow/deny
копией. `dependencyConvergence` оставлен как явный долг.

## Порядок внедрения

1. ArchUnit подключён и закрепляет layer/package rules.
2. Parent reactor и Enforcer введены на этапе 9.
3. Часть гарантий переложена на Maven module dependencies.
4. Отложено: `dependencyConvergence`, Spring Modulith/canvas, JPMS.

## Definition of Done для границы

Граница считается защищённой, когда её нарушение приводит к **красной сборке**
(тест ArchUnit, ошибка компиляции модуля или enforcer), а не только к замечанию
ревьюера.
