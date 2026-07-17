# Документация ioc-extractor

Авторитетный комплект документации проекта. Организован по **пяти бакетам**, у
каждого — свой вопрос-тест «что сюда кладём». Кладём документ в тот бакет, чей
тест он проходит; не дублируем между бакетами — вместо этого ссылаемся.

| Бакет | Тест: «этот документ…» |
|---|---|
| **`docs/*.md`** (корень, UPPERCASE) | …описывает весь проект или правило, действующее везде? (карты и закон) |
| **[dev/](dev/)** | …объясняет, как внутри устроена одна способность, для того, кто будет менять её код? |
| **[ADR/](ADR/)** | …фиксирует, почему мы приняли решение X на момент времени? (immutable) |
| **[guides/](guides/)** | …рассказывает админу/интегратору, как этим пользоваться, эксплуатировать или расширять? |
| **[worknote/](worknote/)** | …ведёт черновой рабочий диалог, не тянущий на ADR? |

## Карты и закон (корень)

| Документ | О чём |
|---|---|
| [PRINCIPLES.md](PRINCIPLES.md) | Инженерные принципы: SOLID / DRY / OCP / KISS и проектные тенеты (агностичные модули, порты-и-адаптеры, конфигурируемость) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Текущая архитектура: Clean Hexagonal + Onion, слои, правило зависимостей, конвейер обработки |
| [MODULARIZATION.md](MODULARIZATION.md) | Многомодульная структура: Maven-реактор, platform/core/adapters/bootstrap и правила зависимостей |
| [BOUNDARIES.md](BOUNDARIES.md) | Защита архитектурных границ: ArchUnit, Maven Enforcer, JPMS (дорожная карта) |
| [CONVENTIONS.md](CONVENTIONS.md) | Стандарт Javadoc, конвенция «README в каждом каталоге» (+ шаблон), правила кода |
| [RELEASE-PROCESS.md](RELEASE-PROCESS.md) | Сквозной контракт версионирования, сборки, публикации, deployment и сопровождения релизов |
| [SERVICES-CATALOG.md](SERVICES-CATALOG.md) | Каталог бизнес-сервисов (DDD) и карта: ответственности, порты, зависимости, целевые модули |
| [DIAGNOSTICS-CATALOG.md](DIAGNOSTICS-CATALOG.md) | Генерируемая карта диагностических кодов из `DiagnosticCatalogs` |
| [LOGGING-CATALOG.md](LOGGING-CATALOG.md) | Генерируемая карта log actions и полей из `EventAction` / `LogField` |
| [KNOWN-ISSUES.md](KNOWN-ISSUES.md) | Единый реестр технического долга, заложенных seam'ов и статуса этапов (ID, статус, эфф., приоритет) |

## Способности (dev/): как это работает внутри

Технические нарративы по способностям — для того, кто меняет код. Способность
обычно пересекает несколько Maven-модулей; реестр отдельного модуля — в его
`README.md` рядом с кодом.

| Документ | О чём |
|---|---|
| [dev/extraction.md](dev/extraction.md) | Извлечение/нормализация IOC, PSL/onion-классификация и тест-корпус |
| [dev/pipeline.md](dev/pipeline.md) | ETL-конвейер: Pipes-and-Filters (EIP) + Envelope + Result/Either |
| [dev/output-mapping.md](dev/output-mapping.md) | Конфигурируемое заполнение артефактов: провайдеры + трансформации, декларативные колонки |
| [dev/ingestion.md](dev/ingestion.md) | Стриминговый демон-инжест: детект, автомат каталогов, JDBC truth, CSV-проекции, идемпотентность, lifecycle |
| [dev/sync.md](dev/sync.md) | Remote fetch/publish: SMB transport, ledgers, atomic protocols, CLI, scheduler lifecycle и health |
| [dev/event-coordination.md](dev/event-coordination.md) | Event-driven координация: когда применять события, контракт `platform-events`, keyed single-flight, correctness-via-reconcile, anti-broker guardrails, эволюция к брокеру |
| [dev/DIAGNOSTICS.md](dev/DIAGNOSTICS.md) | Диагностика обработки данных: каталог кодов, данные→сообщение, Result/Notification, bridge в logging |
| [dev/LOGGING.md](dev/LOGGING.md) | Operational logging и трассировка: ECS JSON, rolling file, MDC, daemon/oneshot профили |
| [dev/LOGGING-TAXONOMY.md](dev/LOGGING-TAXONOMY.md) | Таксономия логов: ECS mapping, `event.*`, project-specific поля `ioc.*`, связь с diagnostics |
| [dev/CROSS-CUTTING.md](dev/CROSS-CUTTING.md) | Сквозные подсистемы (логирование, диагностика, ошибки) как модули за портами |

> Генерируемая карта `DIAGNOSTICS-CATALOG.md` остаётся в корне (это reference-артефакт
> по всему проекту), а описание того, как устроена подсистема диагностики, живёт в
> `dev/DIAGNOSTICS.md`.

## Решения (ADR/), гайды и worknote

- [ADR/](ADR/) — журнал решений (`NNNN-slug`, сквозная нумерация, append-only):
  почему выбрали то, что выбрали; отклонённые варианты; следствия.
- [guides/](guides/) — практические руководства для админов/интеграторов
  (первый — работа с удалённым SMB-хранилищем, en + `ru/`).
- [worknote/](worknote/) — живые рабочие заметки (обсуждения с ИИ-агентами,
  временные issue-списки); выносится в gitignore.

## Принципы ведения документации

- Каждый значимый каталог сопровождается своим `README.md` — справочник модуля
  рядом с кодом (см. [CONVENTIONS.md](CONVENTIONS.md#readme-в-каждом-каталоге)).
- ADR — immutable: принятое решение не редактируем под новую реальность, а
  добавляем supersede-ADR (или датированную надстройку) со ссылкой на старое.
- После фичи или значимого рефактора обновляем затронутые доки в том же
  изменении — dev-доку способности и задетые карты. Устаревший док — как
  падающий тест.
- Генерируемые доки (напр. `DIAGNOSTICS-CATALOG.md`) генерируются, а не правятся
  руками: что можно вывести из кода — выводим, оно не рассинхронизируется.
- Документы пишутся на русском; код, Javadoc и идентификаторы — на английском.
