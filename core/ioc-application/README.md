# core/ioc-application

## Назначение

Application layer: use-case ports, IOC ETL payloads/stages, extraction
orchestration и storage-neutral Artifact Emission contracts, model,
formation saga/change detection/forward recovery.

**Правило слоя:** application работает через ports and domain/platform
contracts. It does not import concrete adapters, Spring or runtime logging.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/application/` | Ports, use cases, payloads and stages |
| `src/test/java/com/iocextractor/application/` | Application and stage tests |

Artifact Emission разделяет orchestration на три узких компонента:
`ExportService` координирует новый run, `ExportChangeDetector` содержит чистую
revision/hash policy, `ExportRunRecoveryService` продвигает crash checkpoints
только из ledger + manifest/filesystem evidence. Ни один из них не зависит от
JDBC, CSV/JSON, path API, Spring или SLF4J.

## Зависимости

**Зависит от:** `ioc-domain`, `ioc-platform-etl`,
`ioc-platform-diagnostics`.

**Не импортируется:** adapters, bootstrap, Spring, Tika, CSV, picocli, Logback.
