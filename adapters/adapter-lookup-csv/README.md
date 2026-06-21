# adapters/adapter-lookup-csv

## Назначение

Outbound lookup adapter implementing `LookupRepository` over CSV reputation
artifacts.

**Правило слоя:** contains CSV lookup/storage details only; dedup flow remains
in application stages.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/lookup/` | CSV lookup repository |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-domain`, platform errors/observability,
Commons CSV/IO, SLF4J API.

**Не импортируется:** bootstrap and sibling adapters.
