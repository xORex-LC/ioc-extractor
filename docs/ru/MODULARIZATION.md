# Карта модулей

[English version](../MODULARIZATION.md)

Maven reactor разделяет доменное поведение, прикладную оркестрацию, общие
технические контракты, технологические интеграции и runtime composition.
[ARCHITECTURE.md](ARCHITECTURE.md) описывает их взаимодействие,
[PRINCIPLES.md](PRINCIPLES.md) направляет выбор ответственности,
[BOUNDARIES.md](BOUNDARIES.md) связывает границы с проверками.

## Топология реактора

Корневой [pom.xml](../../pom.xml) объявляет 31 проект: один parent/aggregator,
26 функциональных JAR и четыре build-only report POM. Из JAR 25 содержат
production code, один предназначен для общих тестовых контрактов. Только
[bootstrap/ioc-app](../../bootstrap/ioc-app/README.md) проходит Boot repackage
и является runnable.

Таблицы задают ответственность модулей. README каждого каталога владеет справочником
пакетов/API, его POM — точными прямыми зависимостями.
Знак † обозначает один из шести отдельно опубликованных общих артефактов.

### Platform

| Модуль | Ответственность и граница зависимостей |
|---|---|
| [platform-errors](../../platform/platform-errors/README.md) | Корневой контракт исключения продукта; только JDK |
| [platform-diagnostics-api](../../platform/platform-diagnostics-api/README.md) † | Общие значения, results/policies, bounded collectors и sinks; только JDK |
| [platform-diagnostics](../../platform/platform-diagnostics/README.md) | IOC-каталоги диагностики, metadata, rendering и exceptions; общая диагностика и ошибки продукта |
| [platform-etl](../../platform/platform-etl/README.md) | Независимый от фреймворков Pipes-and-Filters runner; diagnostics/error contracts, без связи с domain/application |
| [platform-events](../../platform/platform-events/README.md) † | Локальные publish-only control-event contracts и recording fixture; только JDK |
| [platform-concurrency](../../platform/platform-concurrency/README.md) † | Keyed asynchronous serialization и synchronous exclusion; только JDK |
| [platform-logging-slf4j](../../platform/platform-logging-slf4j/README.md) † | Типизированные logs, восстанавливающий MDC scope, scalar ECS profile и явные masks; SLF4J API, provider выбирает consumer |
| [platform-observability](../../platform/platform-observability/README.md) | IOC logging catalogs/facades и ETL observer; общая logging library, ETL и SLF4J |
| [platform-diagnostics-logging](../../platform/platform-diagnostics-logging/README.md) | Сервисный мост diagnostics → logging; IOC diagnostics и observability |

Platform — набор связных подсистем. Само имя не означает, что каждый модуль
универсален или зависит только от JDK. IOC vocabulary и текущие diagnostics/error
dependencies ядра ETL остаются внутренними; публикация ETL отложена.

### Core

| Модуль | Ответственность и граница зависимостей |
|---|---|
| [ioc-domain](../../core/ioc-domain/README.md) | IOC model, refang/extraction, attribution, features и classification contracts; без production dependencies |
| [ioc-processing](../../core/ioc-processing/README.md) | IOC views, composition классификации, field mapping и semantic reuse в рамках вызова; domain и ошибки продукта |
| [ioc-application](../../core/ioc-application/README.md) | Use cases, внутренние порты, pipelines, admission/order, canonical/lifecycle/import/export и sync orchestration; domain, processing и platform contracts без фреймворков |
| [ioc-application-tck](../../core/ioc-application-tck/README.md) | Общие JUnit/AssertJ port contracts; тестовая поддержка, подключаемая адаптерами в test scope и исключённая из production analysis/coverage denominators |

Domain остаётся одним IOC bounded context. Capability-пакеты и их DAG дают
внутреннюю структуру без отдельного JAR на каждую capability. Такие application
capabilities, как lifecycle и Artifact Emission, используют существующие
порты/адаптеры; отдельная бизнес-ответственность не обязана создавать Maven-модуль.

### Adapters

| Модуль | Ответственность и семейство интеграции |
|---|---|
| [adapter-config-spring-boot](../../adapters/adapter-config-spring-boot/README.md) † | Проверка имён/источников по record schema и наблюдения миграций; Spring Boot/Core. IOC schemas, загрузка источников и startup enforcement остаются в bootstrap |
| [adapter-events-spring](../../adapters/adapter-events-spring/README.md) † | Явная Spring control-event publication; events core той же версии, Spring Context и SLF4J. Dispatch configuration и recovery принадлежат сервису |
| [adapter-regex-re2j](../../adapters/adapter-regex-re2j/README.md) | Реализации domain pattern engine; RE2/J и JDK regex fallback |
| [adapter-psl](../../adapters/adapter-psl/README.md) | Host classification; Guava Public Suffix List |
| [adapter-source-tika](../../adapters/adapter-source-tika/README.md) | Извлечение текста документа; семейство Tika parsers |
| [adapter-csv](../../adapters/adapter-csv/README.md) | Строгий CSV import, artifact preparation/projection и slice output; Commons CSV |
| [adapter-processing-camel](../../adapters/adapter-processing-camel/README.md) | Ограниченное исполнение обязательных локальных processing plans; Camel, без права canonical commit |
| [adapter-manifest-json-jackson](../../adapters/adapter-manifest-json-jackson/README.md) | Кодек immutable slice manifest; Jackson |
| [adapter-store-jdbc](../../adapters/adapter-store-jdbc/README.md) | Native canonical storage, lifecycle/history, workspaces/receipts, slots, ledgers и migrations; Spring JDBC, SQLite JDBC и HikariCP |
| [adapter-transport-smb](../../adapters/adapter-transport-smb/README.md) | Remote sync и managed-import ownership/materialization/disposition/notifications; SMBJ |
| [adapter-ingest](../../adapters/adapter-ingest/README.md) | Обнаружение файлов, ownership и ограниченный daemon dispatch; Spring Integration и filesystem lifecycle |
| [adapter-cli-picocli](../../adapters/adapter-cli-picocli/README.md) | Driving CLI, lightweight launch и вывод команд/результатов; picocli и Boot integration |

Каждый adapter объединяет одно семейство интеграции за соответствующими
внутренними контрактами. Тесно связанное семейство может включать несколько
сторонних библиотек. Общий SLF4J logging и переиспользуемые Spring adapters
сохраняют явные технологические зависимости; это не основание переносить их в core.

### Composition и build support

| Проект/каталог | Ответственность |
|---|---|
| [ioc-app](../../bootstrap/ioc-app/README.md) | Composition root продукта, typed configuration/preflight, runtime lifecycle, health и runnable Boot JAR |
| [coverage-report](../../build-support/coverage-report/README.md) | Build-only JaCoCo aggregation и проверка целостности production scopes |
| [spotbugs-report](../../build-support/spotbugs-report/README.md) | Build-only raw/filtered SpotBugs aggregate и проверка точных reviewed findings |
| [cpd-report](../../build-support/cpd-report/README.md) | Build-only source report всего репозитория и duplication-group count ratchet |
| [pmd-report](../../build-support/pmd-report/README.md) | Build-only source policy и отдельный advisory watchlist с проверкой scope/ruleset/report integrity |

[Build-quality](../../build-support/build-quality/README.md) и
[test-quality](../../build-support/test-quality/README.md) — JDK-only verifier
sources и tests, а не Maven projects. Корневые build executions запускают их
проверки. Зависимости report POM задают порядок анализа после production projects;
они не превращают reports в runtime dependencies или publication candidates.

У четырёх reports собственные scope registries. TCK намеренно исключён из
production denominators. [TESTING.md](../TESTING.md) и
[build quality](../dev/build-quality.md) владеют lifecycle, coverage и analyzer policy.

## Направление зависимостей и композиция

Главная внутренняя цепочка: bootstrap → adapters → application → processing → domain.
Bootstrap и adapters также напрямую зависят от нужных внутренних контрактов.
Platform-подсистемы образуют собственный ациклический граф; logging/diagnostic
bridges являются внешними интеграционными потребителями этих контрактов.

Maven dependencies определяют доступность classpath, ArchUnit и Enforcer ограничивают
допустимое использование. Успешный module graph сам по себе не доказывает, что
публичный API скрывает все детали реализации. Используйте узкие контракты и
осознанную Java visibility; подробные проверки — в [BOUNDARIES.md](BOUNDARIES.md).

Spring связывает объекты в bootstrap. Core use cases не превращаются в Spring
services ради injection или transactions; механика JDBC transactions остаётся
в storage adapter. Обязательные document и processed-import plans исполняются
через Camel adapter. Processing session принадлежит вызывающему application
workflow; Camel не владеет semantic cache identity или canonical recovery.

## Владение сборкой и публикацией

Root parent централизует dependency/plugin management и общую development version
`${revision}`. Управляемые inter-module dependencies пока используют
`${project.version}`; management предоставляет версии, не добавляя каждую
управляемую зависимость в classpath каждого дочернего модуля.

Шесть опубликованных артефактов используют `io.github.xorex-lc`, остальные модули —
`com.iocextractor`. Java packages сохраняют документированные имена
`com.iocextractor`. Publication profiles создают standalone consumer POM без
private reactor parent. [Каталог библиотек](../guides/ru/libraries/README.md),
POM модулей и component release records определяют координаты и квалифицированные
выпуски артефактов.

[ADR 0048](../ADR/0048-independent-library-versions-and-reuse.md) принимает независимые
версии библиотек, с events core/adapter как одним exact-version family. Активация
POM/CI остаётся TOOL-9; текущий reactor всё ещё использует shared versions.
Component tags, immutable signed bundles и изолированные repository consumers
отделены от выпуска приложения. [RELEASE-PROCESS.md](../RELEASE-PROCESS.md)
владеет release policy и activation criteria.

Module boundary не является автоматически publication boundary. Новый вынос
требует связного API, владельца, конкретной потребности consumer, приемлемого
dependency closure и независимой qualification. Развивайте принятую библиотеку,
когда ответственность принадлежит ей; service policy остаётся локальной.
См. [руководство разработки](../guides/ru/libraries/development.md).

## Изменение графа модулей

Меняйте module boundary, когда ответственность, изоляция зависимостей или
независимый lifecycle оправдывают дополнительную стоимость сборки/публичного API.
Сохраняйте supported contracts, значимые решения фиксируйте в ADR; исходный
granularity rationale — [ADR 0009](../ADR/0009-modularization-granularity.md).

Добавление/удаление модуля также обновляет root reactor, dependency management,
module references, architecture checks и применимые analyzer/coverage/test
registries в одном change. Generated reports не заменяют source registries.
JPMS, Spring Modulith/canvas и dependency convergence остаются отложенными;
их отсутствие не означает отказа уже принятых проверок.
