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

Root-only AntRun execution в фазе `validate` компилирует общий JDK-only
`build-support/build-quality/BuildQualityVerifier`, прогоняет synthetic-reactor
contract matrix и до начала дочерних проектов сверяет SpotBugs/CPD manifests с
root reactor. PMD source-policy/watchlist manifest и exact rulesets
проверяются там же.
Поэтому новый модуль требует явного disposition сразу, а не после
полного анализа. Финальный build-only модуль `build-support/spotbugs-report`
формирует reactor-wide SpotBugs XML/HTML aggregate; поздний report-integrity
режим выводит ожидаемые пути из того же registry, требует non-empty,
структурно корректные XML/HTML всех 19 production-модулей и aggregate и
запрещает reports у excluded scopes. Exact-baseline gate сравнивает unfiltered
raw findings поэкземплярно: новый, stale, moved или metadata-drifted signal
блокирует сборку вместе с analyzer error, пропущенным применимым модулем или
отсутствующим aggregate. Полная механика и процедура triage описаны в
[build-quality capability](dev/build-quality.md).

Финальный build-only модуль `build-support/cpd-report` выполняет один
repository-wide PMD CPD analysis над положительным allowlist всех 19
production `src/main/java` roots. Общий fail-closed verifier даёт disposition
каждому reactor project и сверяет analyzed set с ordering dependencies и
configured source roots. Перед analysis удаляются stale outputs; после него
поздний режим verifier требует non-empty, structurally valid XML/HTML и точное
соответствие
уникальных XML file paths текущим production Java sources. TCK, test sources,
Maven generated outputs и vendor trees исключены. CPD findings остаются
report-only; analyzer, scope или report-integrity error блокирует `verify`.

Отдельный `build-support/pmd-report` владеет принятой PMD source
policy и ownership/size watchlist. Каждый профиль выполняет один
`aggregate-pmd-no-fork` над теми же 19 production roots и формирует
XML/HTML в раздельных `target/pmd/` и `target/pmd-watchlist/`.
Verifier сверяет точный 25-project disposition, ordering dependencies,
положительные source roots, UTF-8, engine dependencies и оба поимённых
ruleset без category refs/exclusions; analyzer/configuration error, пропавший
или out-of-scope report красит выбранную command. Policy работает
как отдельный regular CI job, watchlist остаётся локально opt-in;
найденные violations в обоих случаях report-only, а обычный
`make verify` не активирует ни один из профилей.

`ioc-domain` дополнительно запрещает Spring, Tika, Commons CSV/IO, Guava,
RE2/J, picocli, HikariCP и sqlite-jdbc. `ioc-application` запрещает Spring,
Commons CSV, SMBJ, JDBC, HikariCP и sqlite-jdbc; отдельное ArchUnit-правило
дублирует этот запрет для managed dataframe-import packages. Точные coordinates принадлежат
соответствующим POM, чтобы документация не стала второй исполняемой allow/deny
копией.

Opt-in profile `dependency-analysis` запускает Maven Dependency Plugin
`analyze-only` над всеми 20 functional JAR-модулями, включая reusable TCK;
root и четыре build-only reporting POM пропускаются самим plugin как
`packaging=pom`. Анализ остаётся advisory и не входит в обычный `verify`: после
semantic triage сохранились доказанные starter/SPI/test-aggregate false
positives, а глобальные ignores ослабили бы signal для новых модулей. В отличие
от SpotBugs и CPD scope/report controls, dependency hygiene осознанно
fail-open для новых модулей.
`dependencyConvergence` остаётся отдельным явным долгом.

## Порядок внедрения

1. ArchUnit подключён и закрепляет layer/package rules.
2. Parent reactor и Enforcer введены на этапе 9.
3. Часть гарантий переложена на Maven module dependencies.
4. Отложено: `dependencyConvergence`, Spring Modulith/canvas, JPMS.

## Definition of Done для границы

Граница считается защищённой, когда её нарушение приводит к **красной сборке**
(тест ArchUnit, ошибка компиляции модуля или enforcer), а не только к замечанию
ревьюера.
