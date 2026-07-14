# core/ioc-application

## Назначение

Application layer: use-case ports, IOC ETL payloads/stages, extraction
orchestration и storage-neutral Artifact Emission contracts, model,
formation saga/change detection/forward recovery.

Extraction возвращает first-class completion/diagnostic outcome. Pure domain
decisions материализуются один раз; application stages используют их
для policy outcome и передают в gated observability port без SLF4J dependency.
`ExtractionCommand.runId` обязателен и задаётся driving boundary: oneshot CLI
создаёт новый correlation id, daemon передаёт durable `ingest_run.run_id`.
`ExtractionResult` возвращает тот же id из terminal envelope.

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
