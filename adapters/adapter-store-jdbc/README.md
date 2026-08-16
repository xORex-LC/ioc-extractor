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
  remain `DISABLED_COMPATIBLE`; the production composition remains on the
  compatibility path until the explicit activation slices.
- `JdbcLifecycleControlStore` uses one-way CAS and refuses `ACTIVE` until one
  set-based invariant scan proves that every configured active row has complete,
  ordered lifecycle metadata. Lifecycle/public ID ranges are reserved by atomic
  SQLite `UPDATE ... RETURNING`; allocator state survives active/history cleanup.
- `JdbcCanonicalLifecycleWriter` is the P3 transaction boundary for insert,
  active renewal, due-row archive/recreate, provenance, observation replay,
  insert-driven revision, projection generation and typed receipt staging. It
  samples one write-owned effective time after acquiring SQLite write ownership;
  public and lifecycle ranges are committed beforehand, so rollback creates
  gaps rather than reusable identities. `JdbcConfirmationReceiptWriter`
  publishes `COMPLETE` only after exact artifact-marker and typed-row counts,
  including a valid zero-row artifact.
- `JdbcActiveArtifactReader` applies the half-open active predicate with an
  explicit caller-owned `asOf`. `JdbcCanonicalArtifactRepository.load` applies
  the same predicate for mutable CSV projection when state is `ACTIVE`, while
  `JdbcSnapshotSliceReader` shares one captured `asOf` between coverage and all
  artifact row cursors in one SQLite read snapshot. `ACTIVATING` reads fail
  closed. The compatibility writer is serialized with activation and is refused
  as soon as activation starts.
- `JdbcExpiredArtifactStore` provides the indexed nearest-deadline and bounded
  archive/delete primitive needed by the lifecycle TCK. P3 does not compose a
  scheduler, startup reconciliation, retention cleanup, health or lifecycle
  events; those remain P4 concerns.
- `JdbcCanonicalArtifactRepository` writes rows with canonical `row_key` and
  `ON CONFLICT(row_key) DO NOTHING`, preserving explicit legacy ids when present.
  It is a commit-only boundary: routing and row mapping finish before this adapter
  is called, so rejected fail-fast runs perform no storage write.
  It returns the actual inserted-row count and advances `artifact_revision` once
  per mutating write in the same transaction. `JdbcArtifactRevisionReader`
  provides change detection without scanning business rows. The compatibility
  writer still uses `JdbcArtifactIdBaseline` while production remains disabled;
  the lifecycle writer owns the durable public-ID allocator after activation.
  Schema v4 or the presence of P3 classes alone is not runtime activation.
- `JdbcRunLedger` stores durable per-file ingest checkpoints in `ingest_run`.
  Startup recovery treats `DB_COMMITTED` as recoverable by replaying the derived
  CSV projection from dataframe truth; failures before that checkpoint are marked
  `FAILED`.
- `JdbcIngestionLedger` uses conditional expected-state transitions. Claim is
  insert-if-absent; archive updates only `CLAIMED`; failure is one conditional
  SQLite upsert. Same-target retries are idempotent and an opposite terminal
  transition cannot overwrite the first winner.
- `JdbcExportRunLedger` stores immutable-slice formation checkpoints in
  `export_run`. A partial unique index enforces one global active run; all state
  changes use expected-status CAS. `COMPLETED`/`SKIPPED` and `export_progress`
  are committed atomically, while an active row survives process crash and blocks
  new work until recovery.
- `JdbcSnapshotSliceReader` streams a whole export profile from one explicit
  SQLite read transaction. Coverage/identity metadata and all `ORDER BY id`
  cursors observe the same WAL snapshot; rows cross the port one at a time.
