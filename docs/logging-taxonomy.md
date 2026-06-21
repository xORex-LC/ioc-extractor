# Таксономия логирования

Минимальная таксономия operational log events. Она фиксирует не полный каталог на
все будущие случаи, а **правила именования и маленькое ядро**, чтобы проект мог
легко добавлять новые поля и `event.action` без ломки схемы.

> Статус: **дизайн**. Документ намеренно компактный: расширяемость важнее
> преждевременного полного словаря.

## Принцип

- ECS-поля используем без переименования.
- Проектные поля кладём только в namespace `ioc.*`.
- `event.action` — стабильный machine-readable глагол.
- `message` — человекочитаемый текст, не контракт.
- Новое поле или action добавляется там, где появляется реальная потребность.
- Diagnostic-поля добавляются только к событиям, связанным с диагностикой
  обработки данных.

## Базовые поля

Минимум для structured log event:

| Поле | Назначение |
|---|---|
| `@timestamp` | время события |
| `log.level` | уровень |
| `message` | человекочитаемый текст |
| `service.name` | `ioc-extractor` |
| `service.version` | версия приложения, когда доступна |
| `event.dataset` | зона события (`ioc-extractor.pipeline`, `ioc-extractor.sink`, ...) |
| `event.action` | действие |
| `event.outcome` | `success`, `failure`, `unknown` |

Дополнительные ECS-поля (`event.category`, `event.type`, `error.*`, `trace.*`,
`process.*`) добавляются по мере необходимости и не обязательны для каждого
события.

## Project fields

Начальный whitelist `ioc.*`:

| Поле | Когда |
|---|---|
| `ioc.run.id` | один pipeline-прогон |
| `ioc.mode` | `oneshot` / `daemon` |
| `ioc.stage` | стадия pipeline |
| `ioc.source.id` | id источника |
| `ioc.source.path` | путь источника |
| `ioc.source.content_hash` | content hash источника |
| `ioc.artifact.name` | имя артефакта |
| `ioc.rows` | число строк |
| `ioc.diagnostic.code` | если событие связано с Diagnostic |
| `ioc.diagnostic.category` | если событие связано с Diagnostic |
| `ioc.diagnostic.severity` | если событие связано с Diagnostic |

**Корреляция** run/source — каноничные project fields `ioc.run.id` /
`ioc.source.id`. ECS `trace.id` / `transaction.id` используем только при наличии
совместимого внешнего tracing/APM-контекста, чтобы не подменять семантику ECS.
**Длительности** — ECS `event.duration` (нс), а не кастомный `ioc.duration_ms`.

Per-item поля (`ioc.indicator.*`, `ioc.dedup.key`) не входят в базовый набор:
они допускаются только для `DEBUG`/`TRACE` и добавляются отдельным решением.

## Seed actions

Стартовый набор `event.action`, достаточный для этапа 6:

| Action | Dataset | Когда |
|---|---|---|
| `app_start` | `ioc-extractor.app` | приложение стартует |
| `app_stop` | `ioc-extractor.app` | graceful shutdown |
| `command_start` | `ioc-extractor.cli` | CLI command началась |
| `command_complete` | `ioc-extractor.cli` | CLI command завершилась |
| `stage_start` | `ioc-extractor.pipeline` | стадия началась |
| `stage_complete` | `ioc-extractor.pipeline` | стадия завершилась |
| `lookup_load` | `ioc-extractor.lookup` | lookup artifact загружен |
| `artifact_write` | `ioc-extractor.sink` | CSV artifact записан |

Для daemon-этапа добавятся ingest/partition/aggregation actions рядом с кодом,
который их реально вводит.

## Diagnostic mapping

Diagnostic не определяет log event, а только добавляет diagnostic-поля:

| Diagnostic | Log field |
|---|---|
| `DiagnosticCode.id()` | `ioc.diagnostic.code` |
| `DiagnosticCategory` | `ioc.diagnostic.category` |
| `Severity` | `ioc.diagnostic.severity` и/или `log.level` mapping |
| `cause` | `error.*`, если есть исключение |

Обычные SLF4J/ECS log events могут существовать без `DiagnosticCode`.

## Правила расширения

1. Сначала использовать ECS-поле, если оно подходит по смыслу.
2. Если ECS-поля нет — добавить поле под `ioc.*`.
3. Новый `event.action` добавлять вместе с первым producer’ом события.
4. `message` можно менять свободно; `event.action` и имена полей считаются
   стабильным контрактом.
5. Поля с IOC/token/query не выводить на `INFO` без маскирования или short hash.
6. При росте набора полей перевести таблицы в generated docs из code constants,
   но не делать генератор на старте.
