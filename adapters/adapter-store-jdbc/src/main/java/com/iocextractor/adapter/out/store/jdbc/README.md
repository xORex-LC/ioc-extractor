# com.iocextractor.adapter.out.store.jdbc

## Назначение

JDBC storage adapter internals: datasource creation, SQLite runtime policy,
schema migration mechanics, dataframe schema reconciliation and repository
implementations for storage ports.

**Правило слоя:** this package may use JDBC, Hikari, SQL and database-specific
mechanics. It must expose only application-port implementations and storage VO
types to bootstrap; domain/application do not import this package.

## Структура

| Файл / группа | Назначение |
|---|---|
| `Sqlite*` | SQLite-specific datasource and PRAGMA policy |
| `JdbcIngestionLedger`, `JdbcRunLedger` | Source-ingestion state и CAS checkpoints write→project saga |
| `JdbcCanonicalArtifactRepository`, `JdbcArtifactIdBaseline`, `JdbcArtifactRevisionReader` | Canonical truth, public id baseline и cheap revision read side |
| `JdbcLifecycleControlStore`, `JdbcLifecycleMetadataInspector` | One-way activation CAS и set-based barrier против legacy/partial lifecycle metadata |
| `JdbcLifecycleIdAllocator`, `JdbcArtifactIdAllocator` | Durable atomic range reservation; public allocator сохраняет direction/identity epoch и не зависит только от active `MAX(id)` |
| `JdbcArtifactProjectionWorkStore` | Required/projected generation read и acknowledgement CAS для mutable projection convergence |
| `LifecycleArtifactSchemaPlanner` | Additive lifecycle columns, typed history/source-summary/receipt mirrors и deadline/retention indexes per artifact |
| `JdbcExportRunLedger`, `JdbcExportProgressStore` | Formation-saga CAS/single-flight, terminal progress и latest-run health read model |
| `JdbcRemoteFetchLedger`, `JdbcPublishLedger` | Durable sync fetch idempotency и per-target publish saga state |
| `JdbcImportDeliveryLedger` | Global import sequence, forward-only checkpoint CAS, retry и head-order authority в service schema v9 |
| `JdbcImportWorkspace`, `JdbcImportWorkspaceWriter` | Per-delivery SQLite staging, hard limits, deterministic duplicate finalization и sealed-stage verification |
| `JdbcCanonicalImportWriter` | Read-only stage attach, active-only planning и одна cross-artifact canonical transaction с replay receipt |
| `ImportFileDigests` | Единый byte-level SHA-256 алгоритм для adapter-owned import files; вызывающие владельцы сохраняют разные operator diagnostics |
| `JdbcWriterAdmission` | Общая fair same-process admission для canonical confirmation, import, expiry и slot reconciliation |
| `JdbcSnapshotSliceReader`, `JdbcExportSlotRegistry` | Stable reusable slot reconciliation, generation-safe multi-artifact snapshot и callback-streaming public rows |
| `*Schema*` | SQLite `user_version` runner, migration support and dataframe reconciler |
| `Dataframe*` | Table-per-artifact desired schema, additive plan and reconciliation |
`JdbcCanonicalArtifactRepository` reports actual public-row inserts and advances
`artifact_revision` once per mutating write in the same transaction. Provenance-only
updates do not advance the revision. `JdbcArtifactRevisionReader` exposes this marker
without scanning artifact tables and returns revision zero for never-written artifacts.

Dataframe migration v4 создаёт artifact-independent lifecycle control, clock
high-water, activation progress, global/per-artifact allocators, observation and
reconcile markers, projection work и normalized receipt headers/artifact markers.
Config-driven reconciliation добавляет к каждой active table nullable
`_lifecycle_id`, confirmation timestamps и `_valid_until_epoch_ms`, а также typed
`*_history`, `*_history_sources` и `*_receipt_rows`. Nullable lifecycle допустим
только для compatibility/activation migration; публикация `ACTIVE` защищена
aggregate invariant scan. P2 не подключает эти классы в bootstrap и не меняет
старый canonical repository path.

Dataframe migration v5 создаёт `export_slot_assignment`, legacy
`export_slot_free` и `export_slot_state` в той же DB. Migration v8 coalesce-ит
per-hole rows в `export_slot_free_range`, сохраняя assignments и state.
Assignment PK/unique index защищают обе стороны mapping; range PK и end-index
обслуживают smallest-hole, exact preferred и bounded sparse lookup. Registry
split/merge-ит ranges и разрешает occupied request/survivor mismatch внутри
caller-owned canonical transaction. Он включается только для `ACTIVE` artifacts
с внешней колонкой `id`.

`JdbcExportRunLedger` опирается на partial unique-index активных
`STARTED|STAGED|AVAILABLE` rows: single-flight остаётся общим для
нескольких JVM/processes. Каждый transition — expected-status CAS; `affected=0`
требует reread и допускает только идемпотентный тот же/более поздний
checkpoint. `finish` фиксирует progress и `COMPLETED|SKIPPED` в одной
service-DB transaction. `JdbcRunLedger` использует ту же CAS-дисциплину
для ingest saga.

Service migration v9 добавляет `import_delivery` и компактный ordered transition
audit. `JdbcImportDeliveryLedger` резервирует один global monotonic sequence,
проводит state/checkpoint через expected-state/version CAS и не позволяет due
работе обогнать отложенный minimum nonterminal head. Snapshot, contract и stage
evidence записываются только на соответствующей границе transition.

`JdbcImportWorkspace` использует отдельную SQLite DB на delivery. Writer
streaming-ом пишет normalized rows/branches/cells/errors в bounded batches,
создаёт seal-time indexes и set-wise реализует `coalesce|keep-first`. Seal
checkpoint-ит WAL, проверяет integrity, атомарно публикует opaque stage reference
и digest; promotion обязана повторно открыть этот файл read-only и сверить pin.

Dataframe migration v9 добавляет `import_commit`, per-artifact mutation evidence,
safe row rejections и requested-slot resolutions. `JdbcCanonicalImportWriter`
сначала восстанавливается по exact receipt, затем проверяет immutable stage вне
writer admission, заранее резервирует worst-case monotonic IDs и под общей fair
admission выполняет active matching, tri-state merge, row fan-out, lifecycle,
provenance, aliases, preferred slots, revisions и projection generations в одной
dataframe transaction. Temporary planning tables connection-scoped и очищаются
при каждом использовании pooled connection. Сбой до commit оставляет состояние
`before`, а receipt после commit является authority для forward-only replay.
Safe slot evidence сохраняет exact/fallback и preserved survivor mismatch без
IOC payload; strict mismatch отклоняет всю logical row.

`JdbcWriterAdmission` не заменяет SQLite transaction ownership и не используется
для hashing, parsing или file I/O. Composition root передаёт один экземпляр в
ordinary canonical writer, expiry и export-slot reconciliation; import writer
будет подключён к тому же экземпляру только вместе с P7 recovery barrier.

`JdbcRemoteFetchLedger` хранит read-only remote identity (`path + size + mtime`) и
не требует прав на remote move/delete. `JdbcPublishLedger` ключуется по
`(slice_id,target_id)`, хранит operational slice identity и target binding, а
status transitions делает через expected-status CAS. `PENDING|FAILED` и stale
`IN_PROGRESS` формируют retryable read model; `SUCCEEDED|ABANDONED` являются
terminal для delivery-aware retention. Для actuator health есть агрегированный
`GROUP BY endpoint,status` read model по настроенным target без материализации истории;
полный ordered `findAll` остаётся только явным ops API. Reconcile lookup поддерживает
индекс `(profile,slice_name)` и не обходит CAS transitions.

`JdbcSnapshotSliceReader` сначала получает SQLite write ownership и атомарно
reconcile-ит slots с active membership, затем владеет отдельным connection/read
transaction от первого generation/coverage SELECT до завершающего callback.
Все artifact coverage читаются до `begin`; slotted public rows идут по одной в
`ORDER BY export_slot`, который сериализуется во внешнюю колонку `id`.
Пакет не собирает `CanonicalArtifact`: heap зависит от ширины строки и
числа artifacts, но не от числа rows. Consumer exception откатывает read-tx,
закрывает cursor/connection и выходит без подмены exception type.
Повреждённые persisted coverage/identity metadata переводятся в
`EXPORT.SNAPSHOT_READ_FAILED`, а не протекают наружу как parser/runtime errors.

## Зависимости

**Зависит от:** application ports, platform errors, platform diagnostics,
platform observability, Spring JDBC/JDBC, Hikari.

**Не импортирует:** bootstrap and sibling adapters.

## Ограничения

- `SqliteUserVersionSchemaMigrator` currently splits `vN.sql` files as simple
  semicolon-delimited DDL. This is intentional for the service v1 schema; add a
  proper SQL script parser before migrations contain triggers, `BEGIN...END`
  blocks, seed data with semicolons, or string literals containing semicolons.
- `DataframeSchemaReconciler` accepts only additive changes. Existing unexpected
  non-internal columns or type drift are startup-fatal guardrails; `_`-prefixed
  internal columns are intentionally ignored by config drift detection. Lifecycle
  index-name collisions with a different SQL definition also fail closed.
- Export single-flight не заменяет recovery: после crash активная row
  намеренно блокирует новый run до forward-recovery.
