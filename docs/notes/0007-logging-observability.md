# 0007 — Логирование/observability как отдельная подсистема (ECS)

- **Статус:** принято (дизайн)
- **Дата:** 2026-06-21
- **Связано:** [../logging.md](../logging.md), [../logging-taxonomy.md](../logging-taxonomy.md),
  [../diagnostics.md](../diagnostics.md), [../pipeline.md](../pipeline.md),
  [0003-diagnostics.md](0003-diagnostics.md)

## Контекст

Заказчик составил дизайн operational logging ([logging.md](../logging.md) +
[logging-taxonomy.md](../logging-taxonomy.md)) на основе ECS и поставил вопрос:
объединять ли логирование и диагностику в один этап. Ревью дизайна зафиксировало
несколько уточнений и одно отменённое прежнее решение.

## Решения

| # | Вопрос | Выбор | Обоснование |
|---|---|---|---|
| 1 | Log event vs Diagnostic | **Разные подсистемы.** Log event — событие исполнения приложения; Diagnostic — результат/проблема обработки данных | Разные модели, порты, владельцы; снимает прежнее смешение |
| 2 | Формат структурного лога | **ECS** (отменяет [0003](0003-diagnostics.md) №8 — собственную JSON-схему) | Индустриальный стандарт, тулинг, не тащим свою схему |
| 3 | Корреляция | **`ioc.run.id` (run) / `ioc.source.id` (source)** как каноничные app-correlation поля; ECS `trace.id`/`transaction.id` — только при наличии совместимого tracing/APM-контекста; длительности — ECS `event.duration` | Не подменяем ECS tracing семантику обычным run id; сохраняем путь к APM |
| 4 | MDC vs Envelope | **MDC — производное от `Envelope.meta`** (источник истины); заполняется на границе стадии/адаптера, чистится `MdcScope.close()` | Нет дубля состояния; нет протечек в daemon-пуле |
| 5 | `event.dataset` | **Поле события** (per-event/stage), не корреляционный ключ | Корректная ECS-семантика |
| 6 | Размещение | **`platform-observability`** (MdcScope, LogEvent, ECS-конфиг) + отдельный bridge **`platform-diagnostics-logging`** (`LoggingDiagnosticSink`) | Не ломаем независимость базовых platform-модулей; bridge явно зависит на diagnostics+observability |
| 7 | Этап 6 | **Разбить**: 6 диагностика → 7 pipeline (`Envelope`) → 8 observability/logging | Логирование читает `Envelope.meta`/диагностику — делается после них; меньше риск, ревьюабельнее |

## Следствия

- `ecs.version` не хардкодим (проставляет encoder); daemon по умолчанию `INFO`,
  per-item — по флагу/в коротком окне; `var/logs/` в `.gitignore`.
- Тесты логирования изолированы (in-memory appender / `OutputCapture`, без файлов).
- Документы приведены в соответствие: schema-блок убран из diagnostics.md и
  cross-cutting.md; logging.md — авторитет по логированию; roadmap перенумерован.
- Зависимость к добавлению на этапе 8: `co.elastic.logging:logback-ecs-encoder`
  (после Boot 3.4+ сравнить встроенный structured logging).

## Открытые вопросы

- Точный набор `var/logs/*` и rolling-параметры — при реализации daemon-логирования.
- Маппинг `Severity` диагностики → `log.level` (таблица в logging-taxonomy.md) —
  финализировать с первыми кодами каталога.
