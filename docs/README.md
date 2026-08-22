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
| [PRINCIPLES.md](PRINCIPLES.md) | Инженерные принципы: SOLID / DRY / OCP / KISS и проектные тенеты (агностичные модули, порты-и-адаптеры, конфигурируемость) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Текущая архитектура: Clean Hexagonal + Onion, слои, правило зависимостей, конвейер обработки |
| [MODULARIZATION.md](MODULARIZATION.md) | Многомодульная структура: Maven-реактор, platform/core/adapters/bootstrap и правила зависимостей |
| [BOUNDARIES.md](BOUNDARIES.md) | Защита архитектурных границ: ArchUnit, Maven Enforcer, JPMS (дорожная карта) |
| [CONVENTIONS.md](CONVENTIONS.md) | Стандарт Javadoc, конвенция «README в каждом каталоге» (+ шаблон), правила кода |
| [SECURITY-ENGINEERING.md](SECURITY-ENGINEERING.md) | Secure SDLC policy: активы и trust boundaries, security-контроли, vulnerability lifecycle, gates, evidence и roadmap |
| [THREAT-MODEL.md](THREAT-MODEL.md) | Базовая threat model: risk-based STRIDE по границам доверия, DFD, трассировка актив×угроза×контроль и остаточный риск |
| [RELEASE-PROCESS.md](RELEASE-PROCESS.md) | Сквозной контракт версионирования, сборки, публикации, deployment и сопровождения релизов |
| [DIAGNOSTICS-CATALOG.md](DIAGNOSTICS-CATALOG.md) | Генерируемая карта диагностических кодов из `DiagnosticCatalogs` |
| [LOGGING-CATALOG.md](LOGGING-CATALOG.md) | Генерируемая карта log actions и полей из `EventAction` / `LogField` |
| [KNOWN-ISSUES.md](KNOWN-ISSUES.md) | Единый реестр технического долга, заложенных seam'ов и статуса этапов (ID, статус, эфф., приоритет) |

## Способности (dev/): как это работает внутри

Короткие инженерные guidebook-документы по способностям — для того, кто меняет
их поведение. Они фиксируют устойчивый runtime flow, границы, инварианты,
failure/recovery semantics и точки расширения, но не пересказывают классы,
полный YAML или историю реализации. Точные детали остаются рядом с кодом и в
тестах; каждый guide явно указывает источники истины и триггеры обновления.
Имена имеют форму `lowercase-kebab-case.md`; шаблон — [dev/template.md](dev/template.md).

| Документ | О чём |
|---|---|
| [dev/processing.md](dev/processing.md) | Чтение, refang, extraction, attribution, classification, mapping и policy-gated commit |
| [dev/storage.md](dev/storage.md) | Canonical SQLite, lifecycle/history, canonical identities, reusable export slots и CSV-проекции |
| [dev/ingestion.md](dev/ingestion.md) | Daemon file lifecycle, durable ledgers, retry и recovery |
| [dev/artifact-export.md](dev/artifact-export.md) | Consistent immutable export slices, manifest/marker и export saga |
| [dev/sync.md](dev/sync.md) | Remote fetch/publish, transport boundary, ledgers и reconcile |
| [dev/event-coordination.md](dev/event-coordination.md) | Event hints, consumer-owned admission и correctness-via-reconcile |
| [dev/configuration.md](dev/configuration.md) | Strict `ioc.*` boundary, precedence, validation и registry preflight |
| [dev/observability.md](dev/observability.md) | Diagnostics, failure policy, typed ECS logging, MDC и redaction |
| [dev/build-quality.md](dev/build-quality.md) | Maven quality lifecycle, SpotBugs exact ratchet, CPD, coverage and dependency-analysis controls |
| [dev/canonical-record-lifecycle.md](dev/canonical-record-lifecycle.md) | Fixed canonical validity, expiry/history reconciliation, active reads and recovery |

> Генерируемые `DIAGNOSTICS-CATALOG.md` и `LOGGING-CATALOG.md` остаются
> reference-артефактами в корне; их контракты объясняет `dev/observability.md`.

## Решения (ADR/) и гайды

- [ADR/](ADR/) — журнал решений (`NNNN-slug`, сквозная нумерация, append-only):
  почему выбрали то, что выбрали; отклонённые варианты; следствия.
- [guides/](guides/) — парные en/ru руководства для админов/интеграторов:
  полный configuration reference, deployment/rollback, daemon operations и
  работа с удалённым SMB-хранилищем.

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
