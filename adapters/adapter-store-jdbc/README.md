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

- Mutable streaming returns row count and generation from the same read
  transaction. Safe-clock sampling finishes before opening that read lease, so
  one-connection pools do not require a nested connection. Projection work
  acknowledgement advances only installed coverage; newer required work stays
  pending. The application owner serializes installation and acknowledgement.

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
- `JdbcDocumentPreparationWorkspaceFactory` owns private source pins, process-wide
  memory admission, root-wide disk reservations, leases and inactive-pin retention.
  `DocumentWorkspaceDiskUsage` counts observed file sizes without following links;
  only vanished SQLite sidecars tolerate the listing/attribute-read race.
  `DocumentTextSpool` owns decoded/refanged UTF-16 files; `PagedDocumentText`
  exposes absolute offsets through two fixed pages. `JdbcDocumentSourceWorkspace`
  owns indexed overlap claims and ordered extraction/attribution cursors. Source
  scratch and reducer split the native cache and share one admission lease;
  source scratch is disposable and is removed before seal and on failure.
  One workspace/cache serves all artifacts; its global reducer compares complete
  identity material and preserves whole-row selection and first-key order.
  Candidate canonical admission reserves byte-bounded hydration/transfer chunks,
  both row images and separately bounded main/TEMP caches (512 KiB each).
  Document intake leaves one promotion and one control lease available within
  the existing aggregate budget; public row/field limits are not reduced.
  Sealed repeatable cursors feed canonical writers and typed receipts.
  Version-1 row decoding reads validated slices of the same bounded payload:
  ASCII strings avoid per-field decoder/buffer copies; non-ASCII uses one strict
  UTF-8 decoder per row invocation. Decoded strings own their bytes; missing/null
  cells, field ranks, corrupt-input rejection and sealed replay stay unchanged.
  Receipt cursors can borrow the writer connection; receipt batches flush at 128 rows
  or 1 MiB estimated bytes. An individually larger binding flushes alone;
  document rows have an independent configured encoded-size limit. No
  document-wide winner/receipt list is retained.
- `JdbcCanonicalMatchPlanner` resolves batch key material by digest and exact
  canonical equality against active rows only. Its thread-confined matching
  session uses a direct complete-key singleton query or bounded request staging;
  a lazily created TEMP table is reused until close. `JdbcCanonicalMutationEngine`
  is an immutable factory for transaction-scoped insert/renew/restart/update/clear/no-op
  sessions. Ordinary writes retain one session per artifact; import promotion
  retains one per affected artifact. Each session owns at most 32 prepared
  statements, clears bindings/batches between leases and closes before commit.
  The caller owns the connection, transaction, ID reservations, revision
  aggregation and receipt publication. No session or JDBC resource is global.
- `JdbcCanonicalPreparationContext` binds private native preparation to the
  dataframe reservation authority and a transactional effect sink.
  `JdbcPrivateMutationJournal` pins one private unit/fingerprint, records actual
  row/lifecycle identities and explicit renewal flags, and streams indexed pages
  of at most 256 entries. It owns only its statement, not the native transaction
  or connection. Runtime constructors use a no-op sink; this internal seam does
  not activate versioned storage or publish a canonical receipt.
- `JdbcPublicationProtocol` owns the fenced physical unit/chunk/epoch/flip
  transitions. `JdbcPrivateChangeCapture` records typed native DML in private
  tables after selective hydration; `JdbcPrivateChanges` streams changed
  addresses in indexed pages. Capture does not replace constraints, UPSERT,
  kernel policy or native `PreparedStatement` update counts. Plain SQLite
  `Statement.executeUpdate(sql)` counts trigger work and must not be used for
  cardinality checks on captured tables. These internal candidate components
  are not selected by the default runtime or a schema migration.
- `JdbcTypedPublicationStore` preserves native typed shapes behind committed
  read views. `JdbcPublicationTransfer` streams actual changes in at most
  256-row/1-MiB chunks; initial values, tombstones and explicit renewal flags
  distinguish no-op, replacement and fresh-UTC confirmation. Initialization
  refuses existing business rows; migration and runtime activation are separate.
- `JdbcStagedCanonicalBackend` is an explicitly composed isolated candidate.
  Its native ordinary/import writers and control operations share committed
  visibility, selective private hydration, bounded workspace admission and
  receipt recovery. Fresh publication UTC revalidates selected lifecycles;
  expiry or epoch conflict rebuilds at most three times with unchanged pins
  and burned reservations. Details: [staged publication](../../docs/dev/staged-canonical-publication.md).
  `StagedPublicationFaultIT` qualifies forced child-JVM termination/reopen,
  interruption cleanup, fenced workers, slot-control conflicts and pinned readers
  through real native ports and the service import ledger. Capacity acceptance
  and default-runtime activation remain separate gates.
  Initially empty private families stream their sealed native rows; other families
  preserve typed before-images and final deltas. Prior immutable receipt payloads
  remain canonical, with dependency-pinned actual counts validating completeness.
  Maintenance with no effects does not invalidate prepared promotion epochs.
- `JdbcStagedCanonicalOperations` qualifies candidate-only drained format-12 to
  format-13 conversion, an exclusive local process lease, bounded startup
  cancellation/readiness, and durable rotating physical cleanup. It reuses typed
  transport and internal ID authority. `StagedPublicationOperationsIT` checks
  populated native equivalence, receipt replay without stage, failure rollback,
  live-owner exclusion and coordinated stopped backup/restore. It is not a
  registered production migration or an application configuration switch.
- `JdbcLifecycleControlStore` uses one-way CAS and refuses `ACTIVE` until one
  set-based invariant scan proves that every configured active row has complete,
  ordered lifecycle metadata. Lifecycle/canonical-row ID ranges are reserved by
  atomic SQLite `UPDATE ... RETURNING`; allocator state survives active/history
  cleanup.
- `JdbcCanonicalLifecycleWriter` is the lifecycle transaction boundary for
  insert, active renewal, due-row archive/recreate, provenance, ordered public
  updates, observation replay, revision, projection generation and typed receipt
  staging. It samples one write-owned effective time after acquiring SQLite
  write ownership;
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
  delegates lifecycle-disabled writes to `JdbcCompatibilityArtifactWriter`.
  Legacy rows retain `ON CONFLICT(row_key) DO NOTHING`; opt-in ordered fields use
  the same registration policy/provenance semantics as the lifecycle writer.
  Routing and row mapping finish before this adapter is called, so rejected
  fail-fast runs perform no storage write. Public inserts/updates advance
  `artifact_revision`; same-value origin advancement does not.
  `JdbcArtifactRevisionReader`
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
- Dataframe schema v10 owns the observation-order namespace, monotonic
  registration sequence and field-origin tables. `JdbcObservationRegistrationStore`
  allocates or resumes one rank in a short dataframe transaction and refuses
  namespace drift, missing recovery rows and overflow.
- Dataframe schema v11 versions confirmation payloads, stores ordered-field
  positions, persists public-update/metadata-only commit counts and archives
  lifecycle field origins beside lifecycle history. Receipt v1 is deliberately
  treated as an ETL fallback rather than reinterpreted with v2 semantics.
- Service schema v10 adds CAS journals for document admission and managed-import
  registration references. Service schema v11 atomically adds an
  `import_observation_reservation` marker for each new delivery before dataframe
  rank allocation. Legacy unmarked recovery cannot allocate a rank. Terminal
  retention first asks dataframe storage for a safe exact purge; a live
  provenance/receipt reference retains both registration and service reference,
  while `PURGED` or already `MISSING` allows the service reference to be removed.
  The two databases are never locked in one transaction.
- Service schema v12 stores the active document-processing fingerprint.
  `JdbcDocumentProcessingPolicyGate` permits an unchanged-policy restart, and
  changes the marker only when the caller has drained external ingestion work
  and the service document-admission journal has no nonterminal rows.
- `JdbcObservationRegistrationStatusReader` exposes only pending totals and the
  oldest unresolved oneshot time. The bootstrap health component marks unresolved
  oneshot state `DOWN`; automatic retention excludes live/unresolved oneshot
  registrations and deletes terminal rows only after the receipt/history horizon.
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
- Service schema v13 admits one active immutable export run per profile.
  `JdbcSnapshotSliceReader` bounds concurrent read transactions by the configured
  read budget; slot/clock writes precede read admission.
- Service schema v14 stores document execution attempts, retry-after and failure
  evidence. Recoverable journal selection is indexed by durable admission order.
  Reopening never assigns a new observation/order to queued work.
- One composition-root `JdbcWriterAdmission` is shared by canonical promotion,
  expiry, export slots, lifecycle control and maintenance. Selection prefers
  fresh control work and ages waiting requests into FIFO progress after 100 ms.
  It cannot interrupt an atomic transaction. ID reservation uses CONTROL before
  promotion. Snapshot streaming and workspace preparation do not own this guard.
  Health reads aggregate wait/hold counters and cached workspace pressure without
  listing IOC rows or scanning spill files.
- `JdbcExportRunLedger` stores immutable-slice formation checkpoints in
  `export_run`. A partial unique index enforces one active run per profile; all state
  changes use expected-status CAS. `COMPLETED`/`SKIPPED` and `export_progress`
  are committed atomically, while an active row survives process crash and blocks
  new work until recovery.
- `JdbcSnapshotSliceReader` first reconciles reusable slots in a short SQLite
  writer transaction serialized with lifecycle writes, then streams a whole
  export profile from one explicit read transaction. Generation, coverage and
  identity metadata are validated before callbacks; slot-enabled artifacts use
  `export_slot AS id` in slot order while rows still cross the port one at a time.
