# Таксономия логирования

Минимальная таксономия operational log events. Она фиксирует не полный каталог на
все будущие случаи, а **правила именования и маленькое ядро**, чтобы проект мог
легко добавлять новые поля и `event.action` без ломки схемы.

> Статус: **реализованный seed-контракт этапа 8**. Документ намеренно компактный:
> расширяемость важнее преждевременного полного словаря.

## Принцип

- ECS-поля используем без переименования.
- Проектные поля кладём только в namespace `ioc.*`.
- `event.action` — стабильный machine-readable глагол.
- `event.dataset` в seed-реализации задаётся Logback/ECS encoder-ом как
  статическое `ioc-extractor`; per-event dataset через MDC не используется,
  потому что официальный encoder фильтрует reserved ECS keys из MDC.
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
| `event.dataset` | статическое значение encoder-а: `ioc-extractor` |
| `event.action` | действие |
| `event.outcome` | `success`, `failure`, `unknown` |

Дополнительные ECS-поля (`event.category`, `event.type`, `error.*`, `trace.*`,
`process.*`, `file.*`) добавляются по мере необходимости и не обязательны для
каждого события.

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

Используемые стартовые ECS-поля сверх базового минимума:

| Поле | Когда |
|---|---|
| `file.path` | путь lookup/output файла, если событие связано с файловым IO |

**Корреляция** run/source — каноничные project fields `ioc.run.id` /
`ioc.source.id`. ECS `trace.id` / `transaction.id` используем только при наличии
совместимого внешнего tracing/APM-контекста, чтобы не подменять семантику ECS.
**Длительности** — ECS `event.duration` (нс), а не кастомный `ioc.duration_ms`.

Per-item поля (`ioc.indicator.*`, `ioc.dedup.key`) не входят в базовый набор:
они допускаются только для `DEBUG`/`TRACE` и добавляются отдельным решением.

## Seed actions

Стартовый набор `event.action`, достаточный для этапа 8:

| Action | Area | Когда |
|---|---|---|
| `app_start` | app | приложение стартует |
| `app_stop` | app | graceful shutdown |
| `command_start` | cli | CLI command началась |
| `command_complete` | cli | CLI command завершилась |
| `stage_start` | pipeline | стадия началась |
| `stage_complete` | pipeline | стадия завершилась |
| `lookup_load` | lookup | lookup artifact загружен |
| `source_read` | source | source document прочитан |
| `artifact_write` | sink | CSV artifact записан |
| `diagnostic_emit` | diagnostics | Diagnostic опубликован в log stream |

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
