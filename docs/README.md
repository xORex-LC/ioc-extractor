# Документация ioc-extractor

Авторитетный комплект документации проекта. Организован по **четырём публикуемым бакетам**, у
каждого — свой вопрос-тест «что сюда кладём». Кладём документ в тот бакет, чей
тест он проходит; не дублируем между бакетами — вместо этого ссылаемся.

| Бакет | Тест: «этот документ…» |
|---|---|
| **`docs/*.md`** (корень, UPPERCASE) | …описывает весь проект или правило, действующее везде? (карты и закон) |
| **[dev/](dev/)** (`lowercase-kebab-case.md`) | …объясняет, как внутри устроена одна способность, для того, кто будет менять её код? |
| **[ADR/](ADR/)** | …фиксирует, почему мы приняли решение X на момент времени? (immutable) |
| **[guides/](guides/)** | …рассказывает админу/интегратору, как этим пользоваться, эксплуатировать или расширять? |

## Карты и закон (корень)

| Документ | О чём |
|---|---|
| [PRINCIPLES.md](PRINCIPLES.md), [RU](ru/PRINCIPLES.md) | Принципы ответственности, декларативной политики, переиспользования, durable truth и проверяемых контрактов |
| [ARCHITECTURE.md](ARCHITECTURE.md), [RU](ru/ARCHITECTURE.md) | Текущие runtime flows, слои, владение данными, границы транзакций, recovery и ограничения |
| [MODULARIZATION.md](MODULARIZATION.md), [RU](ru/MODULARIZATION.md) | Ответственность всех Maven-модулей, направления зависимостей, build support и publication boundaries |
| [BOUNDARIES.md](BOUNDARIES.md), [RU](ru/BOUNDARIES.md) | Архитектурные границы, Maven Enforcer и действующие blocking/advisory правила анализаторов |
| [CONVENTIONS.md](CONVENTIONS.md) · [RU](ru/CONVENTIONS.md) | Владение документацией, содержательный Javadoc и единый стиль, README (+ шаблон), языки и правила кода |
| [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md) | Secure SDLC policy: активы и trust boundaries, security-контроли, vulnerability lifecycle, gates, evidence и roadmap |
| [THREAT-MODEL.md](THREAT-MODEL.md) | Базовая threat model: risk-based STRIDE по границам доверия, DFD, трассировка актив×угроза×контроль и остаточный риск |
| [RELEASE-PROCESS.md](RELEASE-PROCESS.md), [RU](ru/RELEASE-PROCESS.md) | Постоянные правила подготовки, версионирования, квалификации, публикации и сопровождения релизов приложения/библиотек |
| [TESTING.md](TESTING.md), [RU](ru/TESTING.md) | Содержательные тесты, coverage floors/ratchets, lifecycle, taxonomy, evidence и flake policy |
| [DIAGNOSTICS-CATALOG.md](DIAGNOSTICS-CATALOG.md) | Генерируемая карта диагностических кодов из `DiagnosticCatalogs` |
| [LOGGING-CATALOG.md](LOGGING-CATALOG.md) | Генерируемая карта log actions и полей из `EventAction` / `LogField` |
| [KNOWN-ISSUES.md](KNOWN-ISSUES.md) | Единый реестр технического долга, заложенных seam'ов и статуса этапов (ID, статус, эфф., приоритет) |

## Способности (dev/): как это работает внутри

Короткие инженерные guidebook-документы по способностям — для того, кто меняет
их поведение. Они фиксируют устойчивый runtime flow, границы, инварианты,
failure/recovery semantics и точки расширения, но не пересказывают классы,
полный YAML или историю реализации. Точные детали остаются рядом с кодом и в
тестах; каждый guide явно указывает источники истины и триггеры обновления.
Имена имеют форму `lowercase-kebab-case.md`; шаблон — [dev/template.md](dev/template.md)
([RU](dev/ru/template.md)).

| Документ | О чём |
|---|---|
| [dev/processing.md](dev/processing.md), [RU](dev/ru/processing.md) | Extraction, attribution, routing, mapping, winner identity and policy-gated commit |
| [dev/storage.md](dev/storage.md), [RU](dev/ru/storage.md) | Canonical SQLite, lifecycle/history, canonical identities, reusable export slots и CSV-проекции |
| [dev/native-capacity.md](dev/native-capacity.md) | Подтверждённые замеры Native, границы проверок и оставшиеся ресурсные ограничения |
| [dev/ingestion.md](dev/ingestion.md), [RU](dev/ru/ingestion.md) | Daemon admission, private source ownership, ordered promotion, retry и recovery |
| [dev/artifact-export.md](dev/artifact-export.md), [RU](dev/ru/artifact-export.md) | Consistent immutable export slices, manifest/marker и export saga |
| [dev/csv-output-format.md](dev/csv-output-format.md), [RU](dev/ru/csv-output-format.md) | CSV bytes, quoting/null semantics, encoding and publication/recovery boundaries |
| [dev/sync.md](dev/sync.md), [RU](dev/ru/sync.md) | Remote fetch/publish, identity and landing limits, ledgers, retry и reconcile |
| [dev/event-coordination.md](dev/event-coordination.md), [RU](dev/ru/event-coordination.md) | Control hints, post-commit boundaries, consumer admission и state-driven recovery |
| [dev/dataframe-import.md](dev/dataframe-import.md), [RU](dev/ru/dataframe-import.md) | Managed local/SMB CSV intake, recognition, staging, promotion и recovery |
| [dev/configuration.md](dev/configuration.md), [RU](dev/ru/configuration.md) | Strict `ioc.*` boundary, effective sources, validation, check/apply and runtime-state limits |
| [dev/configuration-inspection.md](dev/configuration-inspection.md) | Shared Spring Boot name inspection, source coverage and service enforcement boundary |
| [dev/configuration-failure-reports.md](dev/configuration-failure-reports.md), [RU](dev/ru/configuration-failure-reports.md) | Controlled failure categories, schema paths, phase limits and operator rendering |
| [dev/observability.md](dev/observability.md), [RU](dev/ru/observability.md) | Diagnostic retention/delivery, policy/completion, typed logs/MDC, backend bytes и disclosure boundaries |
| [dev/build-quality.md](dev/build-quality.md), [RU](dev/ru/build-quality.md) | Maven quality lifecycle, SpotBugs/CPD/PMD, механика coverage gate и сопровождение областей/отчётов |
| [dev/canonical-record-lifecycle.md](dev/canonical-record-lifecycle.md), [RU](dev/ru/canonical-record-lifecycle.md) | Fixed canonical validity, expiry/history reconciliation, active reads and recovery |

> Генерируемые `DIAGNOSTICS-CATALOG.md` и `LOGGING-CATALOG.md` остаются
> reference-артефактами в корне; их контракты объясняет `dev/observability.md`.

## Решения (ADR/) и гайды

- [ADR/](ADR/) — журнал решений (`NNNN-slug`, сквозная нумерация, append-only):
  почему выбрали то, что выбрали; отклонённые варианты; следствия.
- [guides/](guides/) — operator/integrator guidance: configuration,
  deployment/rollback, daemon operations, canonical lifecycle, aggregate and
  managed dataframe import, remote SMB storage and library publication.
- [Каталог библиотек](guides/libraries/README.md) и
  [русский каталог](guides/ru/libraries/README.md) помогают выбрать общий модуль.
  Руководство concurrency описывает синхронное исключение, FIFO по ключу,
  ёмкость, ошибки, телеметрию и завершение; его примеры исполняются на JAR.
- [Разработка библиотек](guides/libraries/development.md) и
  [русское руководство](guides/ru/libraries/development.md) описывают обязательный
  поиск готового API, его развитие и согласованный переход на независимые версии.
  Правила версий принадлежат [RELEASE-PROCESS.md](RELEASE-PROCESS.md#reusable-library-versions);
  техническая активация остаётся открытым TOOL-9.
- [ADR 0044](ADR/0044-reusable-diagnostics-contract.md) фиксирует границу общей
  диагностики; [руководство библиотеки](guides/libraries/diagnostics.md) объясняет
  подключение, bounded counts, policy и delivery для другого сервиса.
- [ADR 0045](ADR/0045-reusable-slf4j-logging-contract.md) фиксирует общий SLF4J
  API и scalar ECS profile; [руководство](guides/libraries/logging-slf4j.md) и
  [рецепт Boot ECS](guides/libraries/logging-slf4j-boot.md) содержат проверяемые примеры
  для другого сервиса.
- [Проверка конфигурации в ограниченном контексте](guides/libraries/config-spring-boot-check.md)
  разделяет правила загрузки источников и состав бинов; пример для другого
  сервиса компилируется и проверяется без классов IOC.
- [ADR 0046](ADR/0046-reusable-spring-boot-configuration-inspection.md) фиксирует
  общий контракт проверки имён и наблюдения миграций;
  [руководство](guides/libraries/config-spring-boot.md) описывает подключение и обработку
  неполного покрытия без переноса IOC-политики в библиотеку.
- [JDK-контракты](guides/libraries/events.md) и
  [Spring-адаптер](guides/libraries/events-spring.md) локальных событий объясняют метаданные,
  результаты публикации и ограничения recording publisher; пример использует
  собственное событие другого сервиса. [ADR 0047](ADR/0047-explicit-spring-control-event-adapter.md)
  фиксирует отдельный Spring-адаптер и границы его ошибок. Оба артефакта
  опубликованы; исходные сведения сохраняют записи
  [core](../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md) и
  [adapter](../.github/release-notes/ioc-events-spring-0.3.0-rc.1.md).

Локальные рабочие заметки и временные issue-списки не входят в авторитетный
комплект документации, по умолчанию игнорируются Git и не используются
публикуемыми документами как источник истины или цель ссылок. Release-specific
execution bundle может быть явно добавлен в Git как непубликуемый план и журнал
evidence. Это исключение не делает его проектным контрактом: устойчивый
результат исследования переносится в соответствующий ADR,
capability-документ, security registry, `KNOWN-ISSUES.md` или release notes.

## Принципы ведения документации

- Каждый значимый каталог сопровождается своим `README.md` — справочник модуля
  рядом с кодом (см. [CONVENTIONS.md](CONVENTIONS.md#readme-в-каждом-каталоге)).
- ADR — immutable: принятое решение не редактируем под новую реальность, а
  добавляем supersede-ADR (или датированную надстройку) со ссылкой на старое.
- После изменения capability-контракта обновляем затронутый guide и карты в том
  же change. Переименование класса без изменения flow/invariant не требует
  переписывать guide.
- В dev/ не дублируем version matrix, class inventory, полный configuration
  reference и generated catalogs. Ссылаемся на живой source of truth.
- Генерируемые доки (напр. `DIAGNOSTICS-CATALOG.md`) генерируются, а не правятся
  руками: что можно вывести из кода — выводим, оно не рассинхронизируется.
- Целевой язык документации — английский. В линии 0.3.x действует переходный
  режим: новые документы и содержательные переписывания выполняются на
  английском, а существующий русский документ может оставаться русским, пока
  его перевод явно не включён в scope. Внутри одного документа языки прозы не
  смешиваем. Код, комментарии, Javadoc и идентификаторы — на английском.
