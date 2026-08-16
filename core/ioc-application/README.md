# core/ioc-application

## Назначение

Application layer: use-case ports, IOC ETL payloads/stages, extraction
orchestration и storage-neutral Artifact Emission contracts, model,
formation saga/change detection/forward recovery. Модель canonical record
validity находится в `application.artifact.lifecycle`; её client-shaped driven
ports остаются framework-free и не активируются до JDBC-slices.

Extraction возвращает first-class completion/diagnostic outcome. Pure domain
decisions материализуются один раз; application stages используют их
для policy outcome и передают в gated observability port без SLF4J dependency.
`ExtractionCommand.runId` обязателен и задаётся driving boundary: oneshot CLI
создаёт новый correlation id, daemon передаёт durable `ingest_run.run_id`.
`ExtractionResult` возвращает тот же id из terminal envelope.
Terminal ingestion rejection идемпотентен: driving adapter различает впервые
записанный `REJECTED` и уже durable `ALREADY_REJECTED`, не читая ledger напрямую.

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

**Зависит внутрь от:** domain и framework-free platform contracts для ETL,
diagnostics, control events и keyed concurrency. Точный прямой Maven-граф
задаёт [pom.xml](pom.xml); README описывает роли, но не дублирует dependency
inventory.

**Не импортируется:** adapters, bootstrap, Spring, Tika, CSV, picocli, Logback.
