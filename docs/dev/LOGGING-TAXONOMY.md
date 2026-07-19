# Таксономия логирования

Минимальная таксономия operational log events. Она фиксирует не полный каталог на
все будущие случаи, а **правила именования и маленькое ядро**, чтобы проект мог
легко добавлять новые поля и `event.action` без ломки схемы.

> Статус: **реализованный typed-контракт ADR-0018**. Документ намеренно
> компактный: расширяемость важнее преждевременного полного словаря.

## Принцип

- ECS-поля используем без переименования.
- Проектные поля кладём только в namespace `ioc.*`.
- `event.action` — стабильный machine-readable глагол.
- `event.dataset` задаётся bootstrap ECS encoder-ом как статическое
  `ioc-extractor` в общем context-pair stream; per-event dataset не используется.
- `message` — человекочитаемый текст, не контракт.
- Новое поле или action добавляется там, где появляется реальная потребность.
- Тип `LogField` (`STRING|LONG|BOOLEAN`) является частью стабильного контракта.
- Ambient string correlation идёт через MDC; event-local fields — через SLF4J
  key/value pairs. Numeric/boolean MDC запрещён.
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
| `event.dataset` | статическое значение bootstrap ECS encoder-а: `ioc-extractor` |
| `event.action` | действие |
| `event.outcome` | `success`, `failure`, `unknown` |

Дополнительные ECS-поля (`event.category`, `event.type`, `error.*`, `trace.*`,
`process.*`, `file.*`) добавляются по мере необходимости и не обязательны для
каждого события.

## Project fields

Полный актуальный список project- и используемых ECS-полей генерируется из
`LogField` в [LOGGING-CATALOG.md](../LOGGING-CATALOG.md). Это reference-каталог;
данный документ фиксирует правила их выбора и расширения.

**Корреляция** run/source — каноничные project fields `ioc.run.id` /
`ioc.source.id`. ECS `trace.id` / `transaction.id` используем только при наличии
совместимого внешнего tracing/APM-контекста, чтобы не подменять семантику ECS.
**Длительности** — ECS `event.duration` (нс), а не кастомный `ioc.duration_ms`.
JSON type каждого field публикуется в generated catalog; смена типа требует
mapping migration и rollover/reindex существующего Elasticsearch index.

Per-item поля (`ioc.indicator.*`, `ioc.dedup.key`) не входят в базовый набор:
они допускаются только для `DEBUG`/`TRACE` и добавляются отдельным решением.

## Actions

Полный актуальный список `event.action` генерируется из `EventAction` в
[LOGGING-CATALOG.md](../LOGGING-CATALOG.md). Новый action добавляется только
вместе с первым production producer'ом события.

## Diagnostic mapping

Diagnostic не определяет log event, а только добавляет diagnostic-поля:

| Diagnostic | Log field |
|---|---|
| `DiagnosticCode.id()` | `ioc.diagnostic.code` |
| `DiagnosticCategory` | `ioc.diagnostic.category` |
| `Severity` | `ioc.diagnostic.severity` и `log.level` по таблице ниже |
| `cause` | `error.*`, если есть исключение |

Обычные SLF4J/ECS log events могут существовать без `DiagnosticCode`.

| Diagnostic severity | `log.level` |
|---|---|
| FATAL | error |
| ERROR | error |
| WARN | warn |
| INFO | info |
| DEBUG | debug |
| TRACE | trace |

`FATAL` намеренно совпадает с `ERROR` на уровне `log.level`. Различение остаётся
в `ioc.diagnostic.severity`; останов обработки определяет `FailurePolicy`.

## Правила расширения

1. Сначала использовать ECS-поле, если оно подходит по смыслу.
2. Если ECS-поля нет — добавить поле под `ioc.*`.
3. Новый `event.action` добавлять вместе с первым producer’ом события.
4. `message` можно менять свободно; `event.action` и имена полей считаются
   стабильным контрактом.
5. Поля с IOC/token/query не выводить на `INFO` без маскирования или short hash.
6. Каталог actions и полей генерируется из code constants; не дублировать его
   таблицы вручную в capability-доках.
7. Новый field добавлять с первым producer-ом и обязательным JSON scalar type.
   Arbitrary string keys и silent `String.valueOf` fallback запрещены.
8. Event-local field побеждает одноимённый ambient MDC field; duplicate JSON
   members не являются допустимым merge protocol.
