# core/ioc-application

## Назначение

Application layer: use-case ports, IOC ETL payloads/stages, extraction
orchestration и storage-neutral Artifact Emission contracts, model,
formation saga/change detection/forward recovery. Модель canonical record
validity находится в `application.artifact.lifecycle`; её client-shaped driven
ports остаются framework-free. Lifecycle application services координируют common
admission, bounded expiry, independent history retention и durable mutable
projection convergence. P5 добавляет one-way legacy activation и bounded
fingerprinted receipt replay; scheduling, SQL и health не проникают в этот слой.

Extraction возвращает first-class completion/diagnostic outcome. Pure domain
decisions материализуются один раз; application stages используют их
для policy outcome и передают в gated observability port без SLF4J dependency.
`ExtractionCommand.runId` обязателен и задаётся driving boundary: oneshot CLI
создаёт новый correlation id, daemon передаёт durable `ingest_run.run_id`.
`ExtractionResult` возвращает тот же id из terminal envelope.
Terminal ingestion rejection идемпотентен: driving adapter различает впервые
записанный `REJECTED` и уже durable `ALREADY_REJECTED`, не читая ledger напрямую.
`ObservationId` идентифицирует одну delivery/retry цепочку, а повторяемый
`SourceKey` — содержимое; их разделение позволяет поздней одинаковой доставке
подтвердить freshness без нарушения recovery-idempotency.

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

Canonical lifecycle следует той же границе. `LifecycleReconciliationService`
держит один cycle `asOf` и coalesce-ит lossy projection hints по artifact;
`ArtifactProjectionConvergenceService` подтверждает только наблюдённое durable
generation; `LifecycleAdmissionService` сериализует idempotent pre-stateful-work
barrier. Events ускоряют работу, но deadline/projection state и periodic
reconcile остаются authority.
`LifecycleActivationService` возобновляет bounded `existing-records: expire`
до admission, а `ConfirmationReceiptReplayService` использует только complete,
unexpired receipt с точным processing-policy fingerprint и иначе возвращает
обычный ETL fallback.

## Зависимости

**Зависит внутрь от:** domain и framework-free platform contracts для ETL,
diagnostics, control events и keyed concurrency. Точный прямой Maven-граф
задаёт [pom.xml](pom.xml); README описывает роли, но не дублирует dependency
inventory.

**Не импортируется:** adapters, bootstrap, Spring, Tika, CSV, picocli, Logback.
