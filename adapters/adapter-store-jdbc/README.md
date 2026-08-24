# adapters/adapter-store-jdbc

## Назначение

Outbound storage adapter for relational stores. It owns JDBC access, SQL dialect
mechanics, SQLite runtime policy, local transactions, schema migration mechanics,
dataframe schema reconciliation, artifact repositories and run-ledger
checkpoints. As an edge module it also emits storage diagnostics and operational
ECS log events for startup/storage actions.

**Правило слоя:** implements storage ports with Spring JDBC/JdbcClient and JDBC
drivers. Domain and application never import JDBC, SQL, Hikari, SQLite driver or
Spring transaction types.

## Структура

| Подпапка / файл | Назначение |
|---|---|
| `pom.xml` | Maven module descriptor |
| `src/main/java/com/iocextractor/adapter/out/store/jdbc/` | JDBC storage implementations and internal SQL helpers |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/dataframe/` | Versioned dataframe format migrations |
| `src/main/resources/com/iocextractor/adapter/out/store/jdbc/service/` | Versioned service storage migrations |

## Зависимости

**Зависит от:** `ioc-application`, `ioc-platform-errors`,
`ioc-platform-diagnostics`, `ioc-platform-observability`, Spring JDBC, HikariCP,
runtime JDBC drivers.

**Не импортируется:** domain/application internals and sibling adapters.

## Runtime Notes

- The initial service datasource factory opens one Hikari pool. `writeMax` and
  `readMax` are retained as a capacity budget; dedicated read/write pools and
  the strict write-pool=1 topology belong to bootstrap wiring in the ledger
  selector slice.
- SQLite connection PRAGMAs are installed as Xerial `SQLiteConfig` driver
  properties on physical connection creation, not on every pool borrow.
- Storage startup/import events use only catalogued `LogField` keys; pool,
  timeout and import counters therefore share the typed logging schema instead
  of declaring adapter-local string keys.
- Dataframe business tables are reconciled additively from `ioc.sink.artifacts`:
  missing tables/columns are created, order changes are ignored, and
  drop/rename/type drift fails before mutation. Internal `_`-prefixed columns are
  excluded from config drift checks.
- Dataframe format v4 and `LifecycleArtifactSchemaPlanner` install the
  canonical-record lifecycle foundation in the dataframe DB: nullable internal
  lifecycle columns, typed history/source-summary and receipt-row tables,
  deadline/retention indexes, activation/clock state, durable allocators,
  observation markers and mutable-projection generations. Existing databases
  remain `DISABLED_COMPATIBLE` until explicit fixed-validity activation.
- Dataframe format v5 adds the export-owned reusable-slot registry beside
  canonical truth. Format v8 replaces the legacy row-per-hole free table with
  coalesced ranges while preserving assignments and state. The connection-scoped
  `JdbcExportSlotRegistry` seeds the current active mapping, preserves survivors,
  releases vanished lifecycles, splits/merges ranges and resolves exact preferred
  slots or occupied fallback before using its durable high-water. Duplicate
  request groups and strict survivor mismatches fail before registry mutation;
  the caller owns the surrounding canonical transaction.
- Dataframe format v7 adds immutable named match-definition metadata and
  collision-safe active-lifecycle aliases. `JdbcArtifactIdentityStore` stages
  all pending record keys and aliases before one transaction changes durable
  identity state; a collision aborts the whole backfill. Compound v2 record
  keys for `address_blacklist` and `hashes` preserve public/canonical/lifecycle
  IDs, revisions and export-slot ownership.
- `JdbcCanonicalMatchPlanner` resolves batch key material by digest and exact
  canonical equality against active rows only. `JdbcCanonicalMutationEngine`
  is the connection-scoped insert/renew/restart/update/clear/no-op kernel shared
  by the ordinary lifecycle writer and later import promotion; the caller still
  owns the surrounding transaction, ID reservations, revision aggregation and
  receipt publication.
- `JdbcLifecycleControlStore` uses one-way CAS and refuses `ACTIVE` until one
  set-based invariant scan proves that every configured active row has complete,
  ordered lifecycle metadata. Lifecycle/canonical-row ID ranges are reserved by
  atomic SQLite `UPDATE ... RETURNING`; allocator state survives active/history
  cleanup.
- `JdbcCanonicalLifecycleWriter` is the P3 transaction boundary for insert,
  active renewal, due-row archive/recreate, provenance, observation replay,
  insert-driven revision, projection generation and typed receipt staging. It
  samples one write-owned effective time after acquiring SQLite write ownership;
  canonical-row and lifecycle ranges are committed beforehand, so rollback
  creates gaps rather than reusable identities. `JdbcConfirmationReceiptWriter`
  publishes `COMPLETE` only after exact artifact-marker and typed-row counts,
  including a valid zero-row artifact.
- `JdbcActiveArtifactReader` applies the half-open active predicate with an
  explicit caller-owned `asOf`. `JdbcCanonicalArtifactRepository.stream` applies
  the same predicate for mutable CSV projection when state is `ACTIVE`, while
  `JdbcSnapshotSliceReader` shares one captured `asOf` between coverage and all
  artifact row cursors in one SQLite read snapshot. `ACTIVATING` external reads
  fail closed; the internal mutable projection load remains active-filtered so
  activation can atomically install an empty projection before `ACTIVE`. The
  compatibility writer is serialized with activation and is refused as soon as
  activation starts.
- `JdbcExpiredArtifactStore` provides the indexed nearest-deadline and bounded
  archive/delete primitive used by P4 reconciliation. `JdbcLifecycleClock`
  owns the durable nondecreasing UTC high-water and clamp/unsafe policy;
  `JdbcLifecycleReconciliationStore` updates one recoverable cycle checkpoint;
  `JdbcLifecycleHistoryStore` performs indexed bounded retention; and
  `JdbcLifecycleStatusReader` returns aggregate read-only health facts without
  IOC/source identities. Bootstrap owns admission and schedulers.
- `JdbcLifecycleActivationStore` archives legacy rows in resumable keyset
  batches with compact provenance and projection work without advancing the
  insert-driven artifact revision. `JdbcConfirmationReceiptStore` loads only
  complete current receipts and bounds receipt/terminal-observation retention.
- History removal is independent from expiration and is ordered by
  `(closed_at_epoch_ms, history_id)`. Foreign-key cascade removes compact source
  summaries, while lifecycle/canonical-row ID allocators remain monotonic after
  both active and history rows are gone.
- `JdbcCanonicalArtifactRepository` writes rows with canonical `row_key` and
  `ON CONFLICT(row_key) DO NOTHING`, preserving explicit legacy ids when present.
  It is a commit-only boundary: routing and row mapping finish before this adapter
  is called, so rejected fail-fast runs perform no storage write.
  It returns the actual inserted-row count and advances `artifact_revision` once
  per mutating write in the same transaction. `JdbcArtifactRevisionReader`
  provides change detection without scanning business rows. The compatibility
  writer still uses `JdbcArtifactIdBaseline` while production remains disabled;
  the lifecycle writer owns the durable canonical-row ID allocator after
  activation. Schema v4 or the presence of P3 classes alone is not runtime
  activation.
- `JdbcRunLedger` stores durable per-file ingest checkpoints in `ingest_run`.
  Startup recovery treats `DB_COMMITTED` as recoverable by replaying the derived
  CSV projection from dataframe truth; failures before that checkpoint are marked
  `FAILED`.
- `JdbcIngestionLedger` schema v8 keys conditional expected-state transitions by
  `observation_id`; `source_key` is indexed but non-unique. Claim is
  insert-if-absent; archive updates only `CLAIMED`; failure is one conditional
  SQLite upsert. Legacy source-key rows migrate to `legacy:<source_key>`.
- Service schema v9 adds `JdbcImportDeliveryLedger`: one global monotonic import
  sequence, forward-only expected-state/version CAS, state-specific immutable
  checkpoints, durable retry time and a head query that forbids overtaking.
- `JdbcImportWorkspace` keeps bulk import rows outside the service/dataframe
  stores in one opaque per-delivery SQLite file. Batched staging is bounded by
  parser, row/error, per-stage and aggregate watermarks; sealing checkpoints and
  verifies SQLite before atomic rename and digest pinning.
- `JdbcImportCommitEvidenceStore` читает только safe aggregate receipt и row
  issue codes для forward finalization; `JdbcImportStatusReader` выполняет
  indexed aggregate/head queries без locators, filenames, hashes или IOC values.
  Terminal retention выполняет bounded age/count selection per outcome target,
  удаляет dataframe receipt лишь после успешного delete/archive protected
  source/report unit и затем CAS-purge service-ledger row.
- `JdbcExportRunLedger` stores immutable-slice formation checkpoints in
  `export_run`. A partial unique index enforces one global active run; all state
  changes use expected-status CAS. `COMPLETED`/`SKIPPED` and `export_progress`
  are committed atomically, while an active row survives process crash and blocks
  new work until recovery.
- `JdbcSnapshotSliceReader` first reconciles reusable slots in a short SQLite
  writer transaction serialized with lifecycle writes, then streams a whole
  export profile from one explicit read transaction. Generation, coverage and
  identity metadata are validated before callbacks; slot-enabled artifacts use
  `export_slot AS id` in slot order while rows still cross the port one at a time.
