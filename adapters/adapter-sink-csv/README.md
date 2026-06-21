# adapters/adapter-sink-csv

## Назначение

Outbound sink adapter implementing `IocSink` and declarative CSV row mapping.

**Правило слоя:** owns CSV writing and artifact row mapping; domain/application
do not depend on Commons CSV.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/sink/csv/` | CSV sink and mapping components |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-domain`, platform errors/observability,
Commons CSV/IO, SLF4J API.

**Не импортируется:** bootstrap and sibling adapters.
