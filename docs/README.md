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
| [dev/storage.md](dev/storage.md) | Canonical SQLite, row identity/provenance, public ID и CSV-проекции |
| [dev/ingestion.md](dev/ingestion.md) | Daemon file lifecycle, durable ledgers, retry и recovery |
| [dev/artifact-export.md](dev/artifact-export.md) | Consistent immutable export slices, manifest/marker и export saga |
| [dev/sync.md](dev/sync.md) | Remote fetch/publish, transport boundary, ledgers и reconcile |
| [dev/event-coordination.md](dev/event-coordination.md) | Event hints, consumer-owned admission и correctness-via-reconcile |
| [dev/configuration.md](dev/configuration.md) | Strict `ioc.*` boundary, precedence, validation и registry preflight |
| [dev/observability.md](dev/observability.md) | Diagnostics, failure policy, typed ECS logging, MDC и redaction |

> Генерируемые `DIAGNOSTICS-CATALOG.md` и `LOGGING-CATALOG.md` остаются
> reference-артефактами в корне; их контракты объясняет `dev/observability.md`.

## Решения (ADR/) и гайды

- [ADR/](ADR/) — журнал решений (`NNNN-slug`, сквозная нумерация, append-only):
  почему выбрали то, что выбрали; отклонённые варианты; следствия.
- [guides/](guides/) — практические руководства для админов/интеграторов
  (первый — работа с удалённым SMB-хранилищем, en + `ru/`).

Локальные рабочие заметки и временные issue-списки не входят в авторитетный
комплект документации, игнорируются Git и не используются как цели ссылок.
Устойчивый результат исследования переносится в соответствующий ADR,
capability-документ или `KNOWN-ISSUES.md`.

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
- Документы пишутся на русском; код, Javadoc и идентификаторы — на английском.
