# План реализации этапа 9: многомодульность

## Цель

Перевести проект из одного Maven-модуля в Maven-реактор с явными compile-time
границами между `platform`, `core`, `adapters` и `bootstrap`, сохранив текущее
поведение ETL-конвейера, diagnostics, pipeline envelope и observability.

Главный результат этапа: правила Clean Hexagonal/Onion перестают быть только
пакетной дисциплиной и ArchUnit-проверками внутри одного classpath. Базовые
границы начинают защищаться Maven-зависимостями и компилятором.

## Предусловия

- Этапы 0-8 реализованы и зелёные.
- `ArchitectureTest` уже фиксирует пакетные границы, diagnostics, pipeline и
  observability.
- `GoldenPipelineTest` остаётся основным регрессионным guardrail для output.
- Перед переносом кода документация синхронизируется с фактическим состоянием
  этапов 6-8: `architecture.md`, `boundaries.md`, `pipeline.md`,
  `cross-cutting.md`, `modularization.md`, `roadmap.md`.

## Границы этапа

Входит в этап:

- parent POM `ioc-extractor` с `<packaging>pom</packaging>`;
- Maven reactor modules для `platform`, `core`, `adapters`, `bootstrap`;
- выделение reusable ETL/Pipeline kernel отдельно от IOC domain;
- выделение IOC domain shared kernel и доменных capability-модулей;
- централизованное управление версиями зависимостей и plugin management;
- перенос текущих packages в модули без изменения публичного поведения;
- минимальный рефакторинг `application.pipeline`, если он нужен для отделения
  generic pipe-and-flow contracts от IOC-specific stage names/payloads;
- Maven Enforcer guardrails;
- перенос и адаптация unit/golden/architecture tests;
- обновление CI под reactor build;
- обновление README/package-info для новых значимых каталогов и модулей.

Не входит в этап:

- daemon ingestion, ledger, partitions, retry/dead-letter;
- aggregator/retention;
- миграция на JPMS `module-info.java`;
- публикация артефактов во внешний Maven repository;
- переименование Java packages ради новой структуры;
- крупный рефакторинг доменной логики, output mapping или logging taxonomy.

## Текущая точка старта

Сейчас проект физически один Maven-модуль, но код уже разложен по пакетам:

- `common`;
- `diagnostics`;
- `observability`;
- `domain`;
- `application`;
- `adapter`;
- `bootstrap`.

Это позволяет выполнить миграцию в основном как перенос каталогов и POM-ов, не
меняя Java package names. Package names остаются `com.iocextractor...`, чтобы не
создавать лишний diff и не ломать существующие импорты.

## Принципиальная нарезка

В этапе 9 разделяем две разные ответственности, которые сейчас находятся рядом
из-за одномодульной структуры:

1. **ETL/Pipeline kernel** — универсальная pipe-and-flow механика:
   `Envelope`, stage contract, runner, observer, failure-policy integration.
   Этот слой не знает IOC-предметку, CSV, Tika, CLI и конкретный порядок стадий.
2. **IOC domain** — предметная бизнес-логика IOC: refang, extraction,
   normalization/features, classification, attribution и общий язык IOC.

Правило: доменный сервис не знает про `Envelope`; stage знает про `Envelope`, но
не содержит тяжёлую бизнес-логику. Stage адаптирует один domain service или
application port к ETL contract и не знает, какая стадия стоит до или после него.

## Целевая структура реактора

Рабочая структура этапа 9:

```text
ioc-extractor/
├── pom.xml                         # parent reactor, packaging=pom
├── platform/
│   ├── platform-errors/
│   ├── platform-etl/
│   ├── platform-diagnostics/
│   ├── platform-observability/
│   └── platform-diagnostics-logging/
├── core/
│   ├── ioc-domain-kernel/
│   ├── ioc-domain-refang/
│   ├── ioc-domain-extraction/
│   ├── ioc-domain-features/
│   ├── ioc-domain-classification/
│   ├── ioc-domain-attribution/
│   └── ioc-application/
├── adapters/
│   ├── adapter-regex-re2j/
│   ├── adapter-source-tika/
│   ├── adapter-sink-csv/
│   ├── adapter-lookup-csv/
│   ├── adapter-psl/
│   └── adapter-cli-picocli/
└── bootstrap/
    └── ioc-app/
```

`platform-regex` из опубликованной `modularization.md` требует уточнения перед
реализацией: SPI не должен тащить RE2J/JDK implementations во внутренние слои.
На этапе 9 базовое решение такое:

- `PatternEngine` остаётся в `ioc-domain-extraction`, если он является портом
  extraction capability;
- `adapter-regex-re2j` содержит RE2J implementation;
- JDK fallback остаётся в том же adapter-модуле или выделяется позже, если
  появится реальная причина для отдельного артефакта.

Если решим всё же выделять regex SPI отдельно, нужен отдельный
`platform-regex-api`, а не модуль, совмещающий API и implementations.

## Предварительные artifactId

Точные имена фиксируем до реализации, чтобы избежать churn в POM-ах.

| Каталог | ArtifactId | Назначение |
|---|---|---|
| root | `ioc-extractor-parent` | parent reactor, plugin/dependency management |
| `platform/platform-errors` | `ioc-platform-errors` | базовые ошибки/common-типы |
| `platform/platform-etl` | `ioc-platform-etl` | reusable `Envelope`/`Stage`/`PipelineRunner` contracts |
| `platform/platform-diagnostics` | `ioc-platform-diagnostics` | diagnostics model, catalog, result, sink ports |
| `platform/platform-observability` | `ioc-platform-observability` | `MdcScope`, `LogEvent`, taxonomy constants |
| `platform/platform-diagnostics-logging` | `ioc-platform-diagnostics-logging` | bridge `DiagnosticSink` -> operational logs |
| `core/ioc-domain-kernel` | `ioc-domain-kernel` | shared kernel: общий язык IOC |
| `core/ioc-domain-refang` | `ioc-domain-refang` | восстановление дефангнутого текста |
| `core/ioc-domain-extraction` | `ioc-domain-extraction` | обнаружение IOC-кандидатов |
| `core/ioc-domain-features` | `ioc-domain-features` | нормализация и признаки IOC |
| `core/ioc-domain-classification` | `ioc-domain-classification` | match-policy/classification rules |
| `core/ioc-domain-attribution` | `ioc-domain-attribution` | привязка IOC к source context |
| `core/ioc-application` | `ioc-application` | ports, use cases, IOC ETL stage services |
| `adapters/adapter-regex-re2j` | `ioc-adapter-regex-re2j` | `PatternEngine` implementation on RE2J/JDK fallback |
| `adapters/adapter-source-tika` | `ioc-adapter-source-tika` | `SourceReader` через Tika |
| `adapters/adapter-sink-csv` | `ioc-adapter-sink-csv` | CSV sinks and row mapping |
| `adapters/adapter-lookup-csv` | `ioc-adapter-lookup-csv` | CSV lookup repository |
| `adapters/adapter-psl` | `ioc-adapter-psl` | `HostClassifier` через Guava PSL |
| `adapters/adapter-cli-picocli` | `ioc-adapter-cli-picocli` | CLI input adapter |
| `bootstrap/ioc-app` | `ioc-app` | Spring Boot executable jar, composition root |

## Матрица зависимостей

| Модуль | Может зависеть на | Запрещено |
|---|---|---|
| `ioc-platform-errors` | external JDK only | Spring, SLF4J, application/domain/adapters |
| `ioc-platform-etl` | diagnostics, errors | IOC domain/application/adapters/bootstrap, Spring, logging appenders |
| `ioc-platform-diagnostics` | `ioc-platform-errors` | Spring, SLF4J, Logback, application/domain/adapters |
| `ioc-platform-observability` | SLF4J API, возможно `ioc-platform-errors` | domain/application/adapters/bootstrap, Logback-specific appenders |
| `ioc-platform-diagnostics-logging` | diagnostics + observability | domain/application/adapters/bootstrap |
| `ioc-domain-kernel` | errors only if needed | Spring, Tika, CSV, Guava, RE2J, SLF4J, application/adapters/bootstrap, feature modules |
| `ioc-domain-*` capability | `ioc-domain-kernel`, errors, sibling domain modules only by explicit design | Spring, Tika, CSV, picocli, Logback, adapters/bootstrap |
| `ioc-application` | platform-etl, diagnostics, errors, needed IOC domain capabilities | Spring, Tika, CSV, picocli, Logback, concrete adapters/bootstrap |
| `ioc-adapter-*` | `ioc-application`, needed IOC domain modules, needed platform modules, own external lib | bootstrap, соседние adapters без явной причины |
| `ioc-app` | все нужные modules | бизнес-правила и IO-алгоритмы вне composition/wiring |

Особое правило: `ioc-application` может зависеть на `ioc-platform-diagnostics`,
потому что pipeline и `FailurePolicy` используют diagnostics как application
metadata/control contract. Это нужно отразить в `modularization.md`, иначе
документ будет противоречить реализации этапов 6-7.

## Карта переноса packages

| Текущий package | Целевой модуль |
|---|---|
| `com.iocextractor.common` | `platform/platform-errors` |
| `com.iocextractor.diagnostics` | `platform/platform-diagnostics` |
| generic часть `com.iocextractor.application.pipeline` | `platform/platform-etl` |
| `com.iocextractor.observability` без diagnostics bridge | `platform/platform-observability` |
| `com.iocextractor.observability.diagnostics` | `platform/platform-diagnostics-logging` |
| `com.iocextractor.domain.model` | `core/ioc-domain-kernel` |
| `com.iocextractor.domain.refang` | `core/ioc-domain-refang` |
| `com.iocextractor.domain.extract` | `core/ioc-domain-extraction` |
| `com.iocextractor.domain.feature` | `core/ioc-domain-features` |
| `com.iocextractor.domain.classify` | `core/ioc-domain-classification` |
| `com.iocextractor.domain.attribute` | `core/ioc-domain-attribution` |
| `com.iocextractor.application.port`, `application.service` | `core/ioc-application` |
| IOC payload/stage часть `com.iocextractor.application.pipeline` | `core/ioc-application` |
| `com.iocextractor.adapter.out.regex` | `adapters/adapter-regex-re2j` |
| `com.iocextractor.adapter.out.source` | `adapters/adapter-source-tika` |
| `com.iocextractor.adapter.out.sink` | `adapters/adapter-sink-csv` |
| `com.iocextractor.adapter.out.lookup` | `adapters/adapter-lookup-csv` |
| `com.iocextractor.adapter.out.psl` | `adapters/adapter-psl` |
| `com.iocextractor.adapter.in.cli` | `adapters/adapter-cli-picocli` |
| `com.iocextractor.bootstrap` и `IocExtractorApplication` | `bootstrap/ioc-app` |
| `src/main/resources/application.yml`, `logback-spring.xml` | `bootstrap/ioc-app` |

## Ответственность модулей

| Модуль | Делает | Не делает |
|---|---|---|
| `platform-errors` | базовая иерархия исключений и shared error contracts | diagnostics catalog, logging, IOC domain |
| `platform-etl` | generic pipe-and-flow contracts: envelope, stage, runner, observer | IOC-specific stage order, domain rules, IO adapters |
| `platform-diagnostics` | diagnostic codes/model/result/notification/sink ports/rendering | пишет в SLF4J, знает про pipeline stages |
| `platform-observability` | MDC scope, log event helper, field/action constants | настраивает конкретный rolling file для приложения |
| `platform-diagnostics-logging` | маппит diagnostics в operational log events | становится источником diagnostics truth |
| `ioc-domain-kernel` | стабильный язык IOC: indicators, types, source context, raw spans/features if shared | feature algorithms, orchestration, adapters |
| `ioc-domain-*` capability | один доменный шаг: refang/extract/features/classify/attribute | знать про `Envelope`, IO, Spring, logging, соседние стадии |
| `ioc-application` | use cases, ports, IOC ETL stages, composition of pipeline | concrete file/CSV/Tika/picocli implementations, доменные алгоритмы внутри stage |
| `adapter-*` | реализует один technical boundary | управляет use-case flow или доменной политикой |
| `ioc-app` | Spring Boot wiring, config binding, executable jar | реализует бизнес-алгоритмы |

## Порядок работ

### Шаг 1. Синхронизация документации

Обновить:

- `docs/modularization.md`: текущее состояние, зависимость application ->
  diagnostics, разделение ETL kernel и IOC domain, regex API/implementation,
  observability runtime boundary;
- `docs/architecture.md`: добавить diagnostics/observability/pipeline packages и
  уточнить, что application может зависеть на platform diagnostics и
  platform-etl;
- `docs/pipeline.md`: убрать формулировки, что pipeline/envelope ещё только
  будущая эволюция; зафиксировать, что `Envelope`/`Stage` являются ETL kernel,
  а IOC stage implementations остаются application-level filters;
- `docs/boundaries.md`: отразить текущие ArchUnit-правила и будущую роль Maven
  compile boundaries;
- `docs/cross-cutting.md`: заменить устаревший статус проектирования;
- `docs/roadmap.md`: зависимость этапа 9 уточнить до `6,7,8` или пояснить, что
  stage 9 опирается на завершённые platform-контуры.

### Шаг 2. Parent reactor без переноса кода

- преобразовать root `pom.xml` в parent `packaging=pom`;
- создать временный/первый module `bootstrap/ioc-app` или использовать
  промежуточный `app` module для минимального переноса текущего POM;
- перенести зависимости в `<dependencyManagement>` и plugin versions в
  `<pluginManagement>`;
- сохранить `./mvnw test`/`verify` зелёными.

Решение по Spring Boot parent: root parent может оставаться наследником
`spring-boot-starter-parent`, а обычные modules наследуют root parent. Это
упрощает plugin/dependency management на первом переходе. Если позже понадобится
более строгий BOM-only режим, выделим отдельным решением.

### Шаг 3. Platform modules

Вынести:

- `platform-errors`;
- `platform-etl`;
- `platform-diagnostics`;
- `platform-observability`;
- `platform-diagnostics-logging`.

Проверки:

- `platform-etl` не зависит на IOC domain/application/adapters/bootstrap;
- `platform-etl` может зависеть на diagnostics/errors для `Diagnostic`,
  `Notification` и `FailurePolicy`;
- `platform-diagnostics` не зависит на SLF4J/Logback/Spring;
- `platform-observability` не зависит на domain/application/adapters/bootstrap;
- bridge зависит только на diagnostics + observability.

При выносе `platform-etl` нужно отделить generic contracts от IOC-specific
имен стадий и payloads:

- generic: `Envelope`, metadata contract, `Stage`, `Pipeline`,
  `PipelineRunner`, `PipelineObserver`;
- IOC-specific: `StageName` values вроде `READ_SOURCE`/`REFANG`, payload records
  и concrete stage classes.

Если текущий `StageName` enum мешает выделению reusable kernel, заменить его на
generic stage identifier (`StageId`/string value object) или оставить enum в
`ioc-application`, а platform contract сделать независимым от конкретного enum.

### Шаг 4. Core modules

Вынести:

- `core/ioc-domain-kernel`;
- `core/ioc-domain-refang`;
- `core/ioc-domain-extraction`;
- `core/ioc-domain-features`;
- `core/ioc-domain-classification`;
- `core/ioc-domain-attribution`;
- `core/ioc-application`.

Проверки:

- `ioc-domain-kernel` не зависит на capability modules;
- domain capability modules не зависят на `platform-etl` и не знают про
  `Envelope`;
- каждый domain module компилируется без Spring/Tika/CSV/Guava/RE2J/SLF4J;
- `ioc-application` компилируется без concrete adapters and Spring;
- application stages зависят на `platform-etl`, ports, diagnostics и нужные
  domain capabilities;
- stage не содержит доменный алгоритм, а только вызывает один service/port и
  возвращает новый envelope.

Если при переносе отдельный capability module начинает требовать слишком много
соседних domain modules, это считается сигналом пересмотреть shared-kernel
границу, а не поводом складывать всё обратно в общий `common`.

### Шаг 5. Adapter modules

Вынести adapters по одному, чтобы легче локализовать ошибки dependency graph:

1. `adapter-regex-re2j`;
2. `adapter-psl`;
3. `adapter-source-tika`;
4. `adapter-lookup-csv`;
5. `adapter-sink-csv`;
6. `adapter-cli-picocli`.

Каждый adapter получает только свой внешний dependency:

- RE2J только в regex adapter;
- Guava только в PSL adapter;
- Tika только в source adapter;
- Commons CSV/IO только в CSV adapters;
- picocli только в CLI adapter.

### Шаг 6. Bootstrap module

- перенести Spring Boot application entrypoint и composition root в
  `bootstrap/ioc-app`;
- перенести `application.yml`, `logback-spring.xml`, profile/test configs;
- оставить Spring annotations/configuration только в bootstrap и технических
  adapters, если adapter действительно Spring-managed;
- убедиться, что executable jar собирается из `ioc-app`.

### Шаг 7. Тесты и fixtures

Распределение:

- ETL kernel tests -> `platform/platform-etl`;
- IOC domain kernel tests -> `core/ioc-domain-kernel`;
- domain capability tests -> соответствующий `core/ioc-domain-*`;
- diagnostics tests -> `platform/platform-diagnostics`;
- observability tests -> `platform/platform-observability` или bridge module;
- pipeline/stage tests -> `core/ioc-application`;
- adapter unit tests -> соответствующий adapter module;
- Spring context smoke and golden e2e -> `bootstrap/ioc-app`.

Golden fixtures остаются в module test resources того модуля, который запускает
e2e (`bootstrap/ioc-app`). Не использовать production `dataframe/` как test
output.

### Шаг 8. ArchUnit и Maven Enforcer

Сохранить текущий `ArchitectureTest`, но после физической нарезки разделить его
на два уровня:

- module-local ArchUnit tests для package rules внутри модуля;
- root/bootstrap architecture tests для cross-module/package rules, если
  остаются проверки, которые Maven dependency graph не покрывает.

Добавить Maven Enforcer:

- `requireMavenVersion`;
- `requireJavaVersion`;
- `dependencyConvergence`;
- `banDuplicatePomDependencyVersions`;
- при необходимости `bannedDependencies` для запрета Spring/Tika/CSV/Guava/RE2J
  в core/platform modules.

## CI и проверочный контур

Обновить GitHub Actions:

```bash
./mvnw -B -ntp -T 1C verify
```

Локальные команды для разработки:

```bash
./mvnw -B -ntp verify
./mvnw -B -ntp -pl platform/platform-etl -am test
./mvnw -B -ntp -pl core/ioc-domain-classification -am test
./mvnw -B -ntp -pl core/ioc-application -am test
./mvnw -B -ntp -pl bootstrap/ioc-app -am verify
./mvnw -B -ntp -DskipTests package
java -jar bootstrap/ioc-app/target/ioc-app-0.1.0-SNAPSHOT.jar extract --source source/ioc-source.htm
```

Проверки, которые должны остаться зелёными:

- unit tests всех модулей;
- ArchUnit rules;
- diagnostics catalog documentation test;
- logging taxonomy/MDC leak tests;
- golden pipeline e2e;
- Spring context smoke test;
- executable jar smoke run на синтетическом или существующем source fixture.

## Критерии приёмки

- `./mvnw -B -ntp -T 1C verify` зелёный из корня.
- `bootstrap/ioc-app` собирает executable Spring Boot jar.
- CLI `extract --source ...` работает и сохраняет текущий output contract.
- `GoldenPipelineTest` зелёный после переноса.
- Core modules физически не имеют зависимостей на Spring/Tika/CSV/picocli/Logback.
- `platform-etl` физически не имеет зависимостей на IOC domain/application,
  adapters и bootstrap.
- IOC domain modules физически не имеют зависимостей на `Envelope`, `Stage`,
  pipeline runner или observability.
- IOC stage implementations находятся в application layer и остаются
  независимыми filters: один stage не вызывает другой stage и не владеет order.
- External dependencies находятся только в нужных modules.
- `docs/modularization.md`, `docs/architecture.md`, `docs/boundaries.md`,
  `docs/pipeline.md`, `docs/roadmap.md` синхронизированы с итоговой структурой.
- Каждый новый значимый каталог/module имеет `README.md` и при необходимости
  `package-info.java`.

## Открытые вопросы перед реализацией

1. **ETL stage identifier:** переносим ли `StageName` в `ioc-application` или
   заменяем его в `platform-etl` на generic `StageId`/string value object.
   План по умолчанию: platform contract не должен зависеть от IOC-specific enum.
2. **Regex boundary:** оставляем `PatternEngine` в `ioc-domain-extraction` или
   выделяем `platform-regex-api`? План по умолчанию оставляет порт в extraction
   capability и выносит RE2J/JDK implementation в adapter.
3. **Observability config:** `platform-observability` содержит только API/helper,
   а `logback-spring.xml` живёт в `ioc-app`. Нужно подтвердить это как правило.
4. **ArtifactId convention:** принять предложенные `ioc-*` имена или выбрать
   другую схему до создания POM-ов.
5. **Spring Boot parent:** оставить Spring Boot parent у root POM на первом
   этапе или перейти на явный import `spring-boot-dependencies` в
   dependencyManagement.
6. **README debt:** достаточно ли README на module root или нужно закрывать
   package-level README/package-info debt в `adapter/*` и `application/port/*`
   тем же коммитом.

## Риски и меры

| Риск | Мера |
|---|---|
| Случайно протащить Spring или CSV в core | Maven module deps + Enforcer + ArchUnit |
| Смешать ETL kernel и IOC domain | отдельный `platform-etl`, запрет domain -> pipeline deps |
| Превратить `ioc-domain-kernel` в новый `common` | kernel содержит только стабильный язык IOC, не algorithms/utilities |
| Большой diff из-за переносов файлов | переносить modules по шагам, проверять сборку после каждого крупного шага |
| Потерять golden fixtures или test resources | сначала описать test ownership, затем переносить fixtures |
| Bridge diagnostics/logging создаст цикл | держать отдельный `platform-diagnostics-logging` без зависимости на application |
| `ioc-app` станет god-module | ограничить его composition/config/runtime concerns |
| CI станет медленнее или нестабильнее | использовать reactor build `-T 1C`, targeted `-pl/-am`, не писать тесты в project artifacts |

## Необходимые правки после реализации

- Обновить команды запуска в README/AGENTS-facing документации, если путь jar
  изменится на `bootstrap/ioc-app/target/...`.
- Обновить `docs/modularization.md`: статус этапа и фактическую структуру.
- Обновить `docs/services.md`: module map после реального переноса.
- Обновить `docs/roadmap.md`: stage 9 -> `done`.
