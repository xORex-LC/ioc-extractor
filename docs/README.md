# Документация ioc-extractor

Публичный, авторитетный комплект документации проекта. Здесь живут принципы,
архитектура и конвенции — то, на что мы опираемся при разработке и что
публикуем. Рабочие/операционные заметки агента сюда не входят.

## Содержание

| Документ | О чём |
|---|---|
| [principles.md](principles.md) | Инженерные принципы: SOLID / DRY / OCP / KISS и проектные тенеты (агностичные модули, порты-и-адаптеры, конфигурируемость) |
| [architecture.md](architecture.md) | Текущая архитектура: Clean Hexagonal + Onion, слои, правило зависимостей, конвейер обработки |
| [services.md](services.md) | Каталог бизнес-сервисов (DDD) и карта: ответственности, порты, зависимости, целевые модули |
| [pipeline.md](pipeline.md) | Подход к ETL-конвейеру: Pipes-and-Filters (EIP) + Envelope + Result/Either |
| [output-mapping.md](output-mapping.md) | Конфигурируемое заполнение артефактов: провайдеры + трансформации, декларативные колонки (без хардкода) |
| [extraction.md](extraction.md) | Извлечение/нормализация IOC, PSL/onion-классификация и тест-корпус (изоляция от артефактов проекта, case-table + golden) |
| [ingestion.md](ingestion.md) | Стриминговый демон-инжест: детект, конечный автомат каталогов, идемпотентность, партиции+агрегация, жизненный цикл |
| [modularization.md](modularization.md) | Текущая многомодульная структура: Maven-реактор, platform/core/adapters/bootstrap и правила зависимостей |
| [cross-cutting.md](cross-cutting.md) | Сквозные подсистемы (логирование, диагностика, ошибки) как самостоятельные модули за портами |
| [diagnostics.md](diagnostics.md) | Диагностика обработки данных: каталог кодов в коде, данные→сообщение, Result/Notification, bridge в logging |
| [diagnostic-catalog.md](diagnostic-catalog.md) | Генерируемая карта диагностических кодов из `DiagnosticCatalogs` |
| [logging.md](logging.md) | Operational logging и трассировка фонового сервиса: ECS JSON, rolling file, MDC, daemon/oneshot профили |
| [logging-taxonomy.md](logging-taxonomy.md) | Таксономия логов: ECS mapping, `event.*`, project-specific поля `ioc.*`, связь с diagnostics |
| [boundaries.md](boundaries.md) | Защита архитектурных границ: ArchUnit, Maven Enforcer, JPMS (дорожная карта) |
| [conventions.md](conventions.md) | Стандарт Javadoc, конвенция «README в каждом каталоге» (+ шаблон), правила кода |
| [roadmap.md](roadmap.md) | Живой план реализации по этапам: зависимости, критерии приёмки, проверка, статус |
| [techdebt.md](techdebt.md) | Единый реестр технического долга и заложенных seam'ов (ID, статус, эфф., приоритет) |
| [dev/](dev/) | Dev-документация: история архитектурных решений и контекст для разработчиков |

## Статус

Этапы 0–11 реализованы: проект переведён в Maven-реактор с модулями
`platform`, `core`, `adapters` и `bootstrap`; ядро, диагностика, pipeline,
observability, CSV DSL, daemon ingestion, агрегация и packaging проверяются
через reactor build. Документы по архитектуре и границам — живые: расширяются
по мере добавления новых источников, форматов артефактов и экспортных sink-ов.

## Принципы ведения документации

- Каждый значимый каталог сопровождается своим `README.md` (см.
  [conventions.md](conventions.md#readme-в-каждом-каталоге)).
- Документы пишутся на русском; код, Javadoc и идентификаторы — на английском.
- Источник истины по принципам/архитектуре — этот каталог `docs/`. Дублирование
  не плодим: остальные файлы ссылаются сюда.
