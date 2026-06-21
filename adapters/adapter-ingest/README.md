# adapters/adapter-ingest

## Назначение

Inbound file-ingestion adapter for daemon mode. Owns Spring Integration file
watch/poll wiring, filesystem source lifecycle, file-backed ingestion ledger and
retry/dead-letter side effects.

**Правило слоя:** translates filesystem events into `IngestSourceUseCase` calls;
it does not implement IOC extraction rules and does not write canonical
artifacts directly.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/in/ingest/` | Spring Integration flow and filesystem adapters |

## Зависимости

**Зависит от:** `ioc-application`, platform errors/diagnostics/observability,
Spring Integration file support, Spring Retry.

**Не импортируется:** domain internals, concrete CSV sink internals, bootstrap.
