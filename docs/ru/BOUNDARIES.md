# Защита архитектурных границ

[English version](../BOUNDARIES.md)

Правила зависимостей из [ARCHITECTURE.md](../ARCHITECTURE.md) защищены компиляцией,
Maven Enforcer и архитектурными тестами. Нарушение защищённой границы приводит
к отказу исполняемой проверки. Ревью объясняет ответственность и замысел;
сборка обеспечивает перечисленные ниже правила.

## Направление зависимостей

- Domain не зависит от application, adapters, bootstrap и фреймворков.
- Processing компонует IOC-поведение без интеграционных фреймворков. Его
  [POM](../../core/ioc-processing/pom.xml) запрещает настроенные зависимости фреймворков.
- Application зависит внутрь от domain, processing и независимых от фреймворков
  platform-контрактов. Он не импортирует adapters, bootstrap, Spring или logging.
- Parsing, CSV, JDBC, SMB и интеграция Spring принадлежат своим adapters и
  bootstrap. Их типы реализации не должны проникать во внутренние порты.
- Сервисы владеют каталогами, схемами, бизнес-правилами, durable state и recovery.
  Библиотеки владеют документированными техническими контрактами.

[Карта модулей](../MODULARIZATION.md) описывает ответственность модулей. Точные
координаты и версии зависимостей принадлежат корневому POM и POM модулей.

## Исполняемые архитектурные правила

Основные межмодульные правила находятся в
[ArchitectureTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ArchitectureTest.java).
Таблица обозначает проверяемую границу; точный выбор пакетов и исключений
определяется исходным кодом теста.

| Граница | Проверка |
|---|---|
| Слои зависят внутрь; верхнеуровневые пакеты и отдельные platform-подсистемы не образуют циклов | `layers_point_inward`, `no_package_cycles` |
| Domain исключает фреймворки, logging, application pipeline и ingestion | Domain-правила в `ArchitectureTest` и [DomainBoundaryTest](../../core/ioc-domain/src/test/java/com/iocextractor/domain/DomainBoundaryTest.java); запреты domain POM |
| Application исключает adapters, bootstrap, Spring и logging | `application_depends_only_inward`, `application_pipeline_is_framework_and_adapter_free`; Maven dependencies |
| Общая диагностика зависит только от собственных типов и JDK; IOC metadata, exceptions и интеграция доставки остаются локальными | `reusable_diagnostics_depends_only_on_itself_and_jdk`, `diagnostics_core_is_framework_and_adapter_free`; [ADR 0044](../ADR/0044-reusable-diagnostics-contract.md) |
| Общий logging зависит только от собственных типов, JDK и SLF4J | `reusable_logging_depends_only_on_itself_jdk_and_slf4j`, локальный [LoggingArchitectureTest](../../platform/platform-logging-slf4j/src/test/java/com/iocextractor/platform/logging/LoggingArchitectureTest.java); [ADR 0045](../ADR/0045-reusable-slf4j-logging-contract.md) |
| Configuration inspection остаётся вне независимых от фреймворков слоёв; её пакет относится к adapter-слою | `reusable_configuration_depends_only_on_itself_jdk_and_spring`, `configuration_inspection_stays_outside_framework_free_layers`; [ADR 0046](../ADR/0046-reusable-spring-boot-configuration-inspection.md) |
| Ядро ETL исключает IOC domain, application, adapters, logging и фреймворки | `platform_etl_is_generic_and_framework_free` |
| Event contracts остаются JDK-only и исключают broker, serialization и keyed execution | `platform_events_is_framework_free_and_not_a_broker`, `control_events_are_not_serializable`, `keyed_execution_is_not_part_of_platform_events` |
| Spring event adapter зависит только от собственных типов, JDK, event contracts, Spring и SLF4J | `events_spring_depends_only_on_contracts_jdk_spring_and_slf4j`; [ADR 0047](../ADR/0047-explicit-spring-control-event-adapter.md) |
| Concurrency исключает events, фреймворки и бизнес-слои | `platform_concurrency_is_framework_free_and_event_free` |
| Типы JDBC, HikariCP, SQLite и SMBJ остаются вне внутренних слоёв | Правила storage/transport в `ArchitectureTest`; запреты application POM |
| Managed-import core исключает CSV, JDBC, SMBJ, Spring и конкретные pipeline stages | `dataframe_import_core_is_framework_and_integration_free` |
| Snapshot readers и slice writers сохраняют потоковые границы | `streaming_snapshot_reader_does_not_materialize_canonical_artifacts`, `slice_writer_owns_no_jdbc_or_materialized_repository_model` |
| Jackson manifest mapping остаётся в выбранных пакетах adapter, CLI и bootstrap | `jackson_manifest_mapping_stays_in_its_adapter` |
| Adapters не владеют конкретными pipeline stages; stages не владеют порядком конвейера | Pipeline-правила в `ArchitectureTest` и [PipelineModelArchitectureTest](../../core/ioc-application/src/test/java/com/iocextractor/application/pipeline/PipelineModelArchitectureTest.java) |
| Application ports являются интерфейсами, с исключениями для command/result contracts | `ports_are_interfaces` |
| Capability-пакеты domain сохраняют внутренний DAG | `DomainBoundaryTest` |

ArchUnit проверяет production-классы в обычном жизненном цикле JUnit. Локальные
тесты модулей добавляют более узкие правила. Эти проверки не означают, что
каждый пакет или публичный API прошёл полное ручное архитектурное ревью.

## Maven и границы исходного кода

Граф реактора определяет доступность классов проектов при компиляции.
Parent Enforcer требует поддерживаемые версии Java/Maven и отклоняет повторные
объявления зависимостей POM. POM domain, processing и application добавляют
собственные запреты; точные списки находятся в этих POM.

Внедрение через конструктор оставляет настройку фреймворков в composition root.
Узкие порты, осознанная публичная видимость и package-private детали реализации
поддерживают направление зависимостей. Там, где отдельного теста нет,
видимость Java остаётся ответственностью проектирования.

`dependencyConvergence`, JPMS descriptors и Spring Modulith/canvas остаются
отложенными. Успешная сборка реактора не подтверждает внедрение этих проверок.

## Охват анализаторов и проверки отчётов

Проверки качества сборки применяют разные правила блокировки:

| Контроль | Команда/жизненный цикл | Причина отказа |
|---|---|---|
| Целостность охвата и конфигурации | Корневой `validate`, до дочерних сборок | Проект реактора без явного disposition, расхождение охвата/зависимостей/ruleset или некорректная конфигурация |
| SpotBugs | Maven `verify` | Новые, устаревшие, перемещённые или изменившие metadata raw findings относительно точных reviewed identities; ошибки анализатора и целостности отчётов |
| PMD CPD | Maven `verify` | Любое изменение принятого числа duplication groups, ошибки анализатора, охвата исходников или целостности отчётов |
| Принятая PMD source policy | Отдельные `make pmd-analysis` и CI job | Finding правила с нулевым baseline, любое расхождение advisory count по правилу либо ошибки анализатора/охвата/отчётов |
| PMD ownership/size watchlist | Явный `make pmd-watchlist` | Ошибки анализатора/охвата/отчётов; findings остаются информационными |
| Maven dependency analysis | Явный `make dependency-analysis` или Maven profile | Ошибки исполнения инструмента; dependency findings остаются информационными (`failOnWarning=false`) |

Count ratchets CPD и PMD требуют смыслового ревью. То же число может скрывать
замену finding; успешная проверка не означает отсутствие findings.
Изменение принятого count или SpotBugs acceptance проходит процедуру из
[build-quality.md](../dev/build-quality.md).

Текущий охват анализа задаётся версионируемыми реестрами:

- [SpotBugs](../../build-support/spotbugs-report/spotbugs-scope.tsv);
- [CPD](../../build-support/cpd-report/cpd-scope.tsv);
- [PMD](../../build-support/pmd-report/pmd-scope.tsv).

Каждый реестр учитывает весь реактор. Production-код выбирается явно;
тестовый TCK, тесты, generated/vendor code и build-only POM исключены из
production analysis.
[BuildQualityVerifier](../../build-support/build-quality/BuildQualityVerifier.java)
сверяет реестры с топологией реактора и зависимостями отчётных модулей.
Его тесты на синтетическом реакторе проверяют положительные и отрицательные случаи.

Отчёты проверяются после анализа: обязательные XML/HTML существуют и структурно
корректны, пути исходников остаются в охвате, а ошибка анализатора не превращается
в успешный пустой отчёт. Raw и filtered представления SpotBugs выполняют разные
задачи. CPD проводит один анализ исходников всего репозитория; PMD policy и
watchlist имеют отдельные каталоги отчётов и точные rulesets.

У dependency hygiene собственный охват: явный профиль анализирует функциональные
JAR, включая TCK; plugin пропускает POM-проекты. В отличие от реестров анализаторов,
его findings не обеспечивают принятый production allowlist. Шум framework,
SPI и test-aggregate сохраняет явные dispositions.

## Проверки и сопровождение

Документированные команды доступны через корневой [Makefile](../../Makefile).
`make verify` включает архитектурные тесты, Enforcer, SpotBugs и CPD.
PMD source policy является отдельной проверкой и не следует из успешного `verify`.
Правила жизненного цикла тестов и coverage принадлежат [TESTING.md](../TESTING.md).

Изменение границы, охвата анализатора или политики блокировки обновляет исполняемое
правило, затронутые module references и эту карту в одном change. Изменение
ответственности оформляется ADR. Граница завершена, когда репрезентативное
нарушение отклоняется её проверкой, а документация описывает эту проверку.
