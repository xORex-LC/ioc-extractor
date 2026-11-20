# com.iocextractor.adapter.out.store.jdbc

## Purpose and reading map

Implement application storage ports for Native SQLite. Follow the
[module dependency/authority rules](../../../../../../../../../README.md). Public repositories
are entry points; connection-scoped helpers, codecs and workspace internals keep
SQL/resource mechanics local. The groups below are reading routes within one
Java package, not separate Maven modules or new layer boundaries.

## Pools, SQL, schema and health

| File | Responsibility |
|---|---|
| [DataframeArtifactSchema.java](DataframeArtifactSchema.java) | Desired table-per-artifact dataframe schema |
| [DataframeColumn.java](DataframeColumn.java) | Desired dataframe business column. The storage adapter keeps SQL type handling local so configuration and application code stay JDBC-free |
| [DataframeFormatMigrations.java](DataframeFormatMigrations.java) | Versioned migrations for the stable dataframe format tables. Per-artifact business columns are reconciled separately from configuration |
| [DataframeSchemaChange.java](DataframeSchemaChange.java) | One planned dataframe schema mutation |
| [DataframeSchemaPlan.java](DataframeSchemaPlan.java) | Additive schema changes after public-drift planning |
| [DataframeSchemaReconciler.java](DataframeSchemaReconciler.java) | Reconciles configured artifact schemas into SQLite dataframe tables |
| [LifecycleArtifactSchemaPlanner.java](LifecycleArtifactSchemaPlanner.java) | Plans additive lifecycle, history and receipt schema for one artifact |
| [ServiceSchemaMigrations.java](ServiceSchemaMigrations.java) | Versioned schema migrations for the service storage context |
| [SchemaMigrationResult.java](SchemaMigrationResult.java) | Outcome of a versioned SQLite schema migration run |
| [SqliteDataSourceFactory.java](SqliteDataSourceFactory.java) | Create one Hikari pool with physical-driver PRAGMAs and persistent SQLite initialization |
| [SqliteDataSourceSettings.java](SqliteDataSourceSettings.java) | Role/URL and single-pool capacity settings; tuning is interpreted by PRAGMA policy |
| [SqlitePragmaPolicy.java](SqlitePragmaPolicy.java) | Computes SQLite PRAGMA settings. Correctness-sensitive values are hard floors owned by the adapter; host-dependent tuning comes from a named preset |
| [SqlitePragmaSettings.java](SqlitePragmaSettings.java) | Effective SQLite PRAGMA set after correctness floors and tuning presets are applied |
| [SqliteSchemaMigration.java](SqliteSchemaMigration.java) | One monotonic SQLite schema migration backed by SQL text |
| [SqliteSynchronousMode.java](SqliteSynchronousMode.java) | SQLite `PRAGMA synchronous` modes ordered by durability strength |
| [SqliteTempStore.java](SqliteTempStore.java) | SQLite `PRAGMA temp_store` values |
| [SqliteTuningPreset.java](SqliteTuningPreset.java) | Host-tunable SQLite performance presets. Correctness PRAGMAs are not part of the preset surface and are fixed by `SqlitePragmaPolicy` |
| [SqliteUserVersionSchemaMigrator.java](SqliteUserVersionSchemaMigrator.java) | Apply explicitly cataloged versions using user_version; unchecked rollback gap is OPS-15 |
| [JdbcSql.java](JdbcSql.java) | Shared, grammar-safe SQL construction primitives for the JDBC adapter |
| [JdbcStorageHealth.java](JdbcStorageHealth.java) | Snapshot returned by `JdbcStorageHealthProbe`. It is intentionally a storage VO, not an Actuator type, so bootstrap remains the only web/Actuator integration point |
| [JdbcStorageHealthProbe.java](JdbcStorageHealthProbe.java) | Connectivity, foreign keys, WAL and quick_check observation; returns a VO without Actuator |

## Canonical rows, identity, mutation and projection

| File | Responsibility |
|---|---|
| [ArtifactIdAllocatorDefinition.java](ArtifactIdAllocatorDefinition.java) | Configuration identity used to initialize or validate one public-id allocator |
| [JdbcArtifactIdAllocator.java](JdbcArtifactIdAllocator.java) | Independently committed direction-aware canonical ID ranges; distinct from export slots |
| [JdbcArtifactIdBaseline.java](JdbcArtifactIdBaseline.java) | Reads public artifact id baselines from configured JDBC dataframe schemas |
| [JdbcArtifactIdentityStore.java](JdbcArtifactIdentityStore.java) | JDBC-backed guardrail and collision-safe backfill for canonical key formulas |
| [JdbcArtifactProjectionWorkStore.java](JdbcArtifactProjectionWorkStore.java) | SQLite-backed monotonic coverage state for mutable artifact projection convergence |
| [JdbcArtifactRevisionReader.java](JdbcArtifactRevisionReader.java) | JDBC read side for cheap per-artifact canonical change detection |
| [JdbcCanonicalArtifactRepository.java](JdbcCanonicalArtifactRepository.java) | Canonical repository facade and generation-consistent mutable-projection streaming |
| [JdbcCanonicalMatchPlanner.java](JdbcCanonicalMatchPlanner.java) | SQLite set-based active alias matcher |
| [JdbcCanonicalMatchSession.java](JdbcCanonicalMatchSession.java) | Thread-confined alias matcher bound to a connection, immutable schema and effective time |
| [JdbcCanonicalMutationEngine.java](JdbcCanonicalMutationEngine.java) | Immutable mutation catalog shared by ingest and import. Each transaction opens its own thread-confined session; no JDBC resource is retained by this factory |
| [JdbcCanonicalMutationSession.java](JdbcCanonicalMutationSession.java) | Thread-confined mutation session for one immutable artifact catalog and effective time. The caller owns the transaction; close releases statements and private matcher state |
| [JdbcCanonicalMutationSessions.java](JdbcCanonicalMutationSessions.java) | Owns at most one mutation session per configured artifact within one import transaction |
| [JdbcCanonicalSourceRecorder.java](JdbcCanonicalSourceRecorder.java) | Records canonical source observations with one shared occurrence policy |
| [JdbcCanonicalStoredRowReader.java](JdbcCanonicalStoredRowReader.java) | Immutable row decoder shared by import planning and transaction-scoped mutations |
| [JdbcCompatibilityArtifactWriter.java](JdbcCompatibilityArtifactWriter.java) | Owns lifecycle-disabled canonical insert and ordered-field mutation transactions |
| [JdbcOrderedFieldStore.java](JdbcOrderedFieldStore.java) | Connection-scoped persistence for ordered mutable-field values and provenance |
| [JdbcProjectionMutation.java](JdbcProjectionMutation.java) | One compare-and-set policy shared by native, privately prepared and direct projection updates |
| [JdbcConnectionRowSource.java](JdbcConnectionRowSource.java) | An adapter-private row source that can reuse its consumer's transaction connection |
| [JdbcRowSources.java](JdbcRowSources.java) | Keeps receipt reads inside the writer transaction, including with a one-connection pool |
| [JdbcStatementScope.java](JdbcStatementScope.java) | Bounded statement ownership within one caller-owned connection/transaction. Thread-confined; leases clear bindings and batches but only the scope closes cached statements. It never closes or commits the connection |
| [JdbcWriterAdmission.java](JdbcWriterAdmission.java) | Non-preemptive operation admission; aging prevents starvation at transaction boundaries |

## Lifecycle, receipts and dataframe observation authority

| File | Responsibility |
|---|---|
| [JdbcActiveArtifactReader.java](JdbcActiveArtifactReader.java) | SQLite active-only lifecycle snapshot reader for one explicit effective time |
| [JdbcCanonicalLifecycleWriter.java](JdbcCanonicalLifecycleWriter.java) | One-artifact lifecycle confirmation transaction after non-reusable ID reservation |
| [JdbcConfirmationReceiptStore.java](JdbcConfirmationReceiptStore.java) | SQLite complete-receipt reader, retention reaper and observation acknowledgement |
| [JdbcConfirmationReceiptWriter.java](JdbcConfirmationReceiptWriter.java) | Stages typed prepared rows and publishes only structurally complete receipts |
| [JdbcConfirmationValidator.java](JdbcConfirmationValidator.java) | Disk-backed duplicate validation before any canonical ID reservation |
| [JdbcExpiredArtifactStore.java](JdbcExpiredArtifactStore.java) | SQLite indexed deadline lookup and bounded archive/delete transaction |
| [JdbcLifecycleActivationStore.java](JdbcLifecycleActivationStore.java) | SQLite resumable batches for the named `existing-records: expire` policy |
| [JdbcLifecycleArchive.java](JdbcLifecycleArchive.java) | Atomic typed snapshot, compact provenance copy and active-row deletion |
| [JdbcLifecycleClock.java](JdbcLifecycleClock.java) | SQLite-backed nondecreasing lifecycle clock |
| [JdbcLifecycleControlStore.java](JdbcLifecycleControlStore.java) | SQLite-backed compare-and-set store for the one-way lifecycle activation state |
| [JdbcLifecycleHistoryStore.java](JdbcLifecycleHistoryStore.java) | Indexed, bounded SQLite retention for typed lifecycle history |
| [JdbcLifecycleIdAllocator.java](JdbcLifecycleIdAllocator.java) | Atomically reserves committed, never-returned lifecycle-id ranges. Reservations commit independently and must precede canonical SQLite write ownership |
| [JdbcLifecycleMetadataInspector.java](JdbcLifecycleMetadataInspector.java) | Performs set-based activation invariant checks without materializing canonical rows |
| [JdbcLifecycleReconciliationStore.java](JdbcLifecycleReconciliationStore.java) | Constant-cardinality SQLite checkpoint for recoverable expiration reconciliation |
| [JdbcLifecycleStatusReader.java](JdbcLifecycleStatusReader.java) | Read-only aggregate SQLite lifecycle status; never returns IOC or source values |
| [JdbcLifecycleTransactionObserver.java](JdbcLifecycleTransactionObserver.java) | Package-private deterministic test seam around SQLite lifecycle write ownership |
| [JdbcLifecycleTransactions.java](JdbcLifecycleTransactions.java) | SQLite write-ownership and canonical rollback-before-auto-commit-restoration primitives |
| [JdbcObservationRegistrationStatusReader.java](JdbcObservationRegistrationStatusReader.java) | Indexed aggregate status over the dataframe-owned observation authority |
| [JdbcObservationRegistrationStore.java](JdbcObservationRegistrationStore.java) | SQLite order authority: one short write transaction, never nested with service storage |
| [JdbcReceiptRowSource.java](JdbcReceiptRowSource.java) | Two ordered cursors share one SQL snapshot; only the current row's positions are retained |
| [LifecycleIdReservation.java](LifecycleIdReservation.java) | Durable, ascending lifecycle-id range reserved before canonical work |

## Snapshot export and sparse reusable slots

| File | Responsibility |
|---|---|
| [JdbcSnapshotSliceReader.java](JdbcSnapshotSliceReader.java) | Reconciles export-owned slot state and streams all artifacts of one resolved export plan from a single SQLite read snapshot |
| [JdbcExportSlotRegistry.java](JdbcExportSlotRegistry.java) | Connection-scoped sparse slot reconciliation, complete preferred-request validation and free ranges |
| [JdbcImportSlotRequests.java](JdbcImportSlotRequests.java) | Reads immutable planned requests without a delivery-sized Java list. Each cursor closes its statement and results; the promotion still owns the shared transaction connection |
| [PreferredExportSlotConflictException.java](PreferredExportSlotConflictException.java) | Typed pre-commit conflict that the later import planner can map to safe row diagnostics |
| [PreferredExportSlotRequest.java](PreferredExportSlotRequest.java) | One transaction-local preferred export-slot request from canonical promotion |
| [PreferredExportSlotResolution.java](PreferredExportSlotResolution.java) | Safe allocation result used by later import reporting without exposing row values |

## Managed import and its private stage

| File | Responsibility |
|---|---|
| [ImportFileDigests.java](ImportFileDigests.java) | Shared byte-level digest algorithm for adapter-owned import files |
| [ImportWorkspaceLayout.java](ImportWorkspaceLayout.java) | Opaque, traversal-safe per-delivery workspace layout |
| [ImportWorkspaceSchema.java](ImportWorkspaceSchema.java) | Private SQLite staging schema; versioned independently from service/dataframe stores |
| [JdbcCanonicalImportObserver.java](JdbcCanonicalImportObserver.java) | Test/diagnostic seam at durable phases of one canonical import transaction |
| [JdbcCanonicalImportWriter.java](JdbcCanonicalImportWriter.java) | Delivery-wide promotion, TEMP planning, merge/slot evidence and receipt in one dataframe transaction |
| [JdbcImportCommitEvidenceStore.java](JdbcImportCommitEvidenceStore.java) | Indexed bounded reader and retention adapter for dataframe import receipts |
| [JdbcImportWorkspace.java](JdbcImportWorkspace.java) | Per-delivery SQLite workspace with explicit rebuild and sealed read-only verification. Scratch data is isolated under opaque hashed file names |
| [JdbcImportWorkspaceWriter.java](JdbcImportWorkspaceWriter.java) | Streaming JDBC writer and deterministic set-based duplicate finalizer |

## Private document preparation

| File | Responsibility |
|---|---|
| [DocumentPreparationLimits.java](DocumentPreparationLimits.java) | Shared admission and per-item limits; native caches are accounted together across jobs |
| [DocumentRowCodec.java](DocumentRowCodec.java) | Versioned bounded row encoding that preserves absent columns, nulls and field rank |
| [DocumentTextSpool.java](DocumentTextSpool.java) | Ordered literal passes over quota-controlled UTF-16 files, including split tokens |
| [DocumentWorkspaceDiskUsage.java](DocumentWorkspaceDiskUsage.java) | Counts observed regular-file bytes without following links or rereading racing file attributes |
| [DocumentWorkspaceFailures.java](DocumentWorkspaceFailures.java) | Keeps fatal cleanup failures visible while attempting every independently owned release |
| [DocumentWorkspaceFiles.java](DocumentWorkspaceFiles.java) | Durable private metadata and a bounded source copy before a seal can become visible |
| [JdbcDocumentPreparationWorkspace.java](JdbcDocumentPreparationWorkspace.java) | One disk-backed global reducer. The single connection/cache is shared by all artifact cursors |
| [JdbcDocumentPreparationWorkspaceFactory.java](JdbcDocumentPreparationWorkspaceFactory.java) | Source/policy pins, aggregate reservations, per-workspace file leases and owned cleanup |
| [JdbcDocumentSourceWorkspace.java](JdbcDocumentSourceWorkspace.java) | Indexed disposable overlap/occurrence spool. No checkpoint here can authorize a commit |
| [PagedDocumentText.java](PagedDocumentText.java) | Fixed two-page UTF-16 cache. Slices share the owner and do not copy source text |

## Service coordination and legacy replay

| File | Responsibility |
|---|---|
| [JdbcDocumentAdmissionJournal.java](JdbcDocumentAdmissionJournal.java) | Service-DB document admission journal with short SQLite CAS transactions |
| [JdbcDocumentProcessingPolicyGate.java](JdbcDocumentProcessingPolicyGate.java) | Pins the active document policy until every older admission has drained |
| [JdbcExportProgressStore.java](JdbcExportProgressStore.java) | JDBC read side for the last terminal export progress of a profile |
| [JdbcExportRunLedger.java](JdbcExportRunLedger.java) | SQLite-backed export formation ledger with database-enforced per-profile single-flight and compare-and-set state transitions |
| [JdbcImmediateTransactions.java](JdbcImmediateTransactions.java) | SQLite transaction primitives for short coordination-store write transactions |
| [JdbcImportDeliveryLedger.java](JdbcImportDeliveryLedger.java) | Service-owned global delivery order and state/version CAS; no canonical mutation authority |
| [JdbcImportStatusReader.java](JdbcImportStatusReader.java) | Indexed value-free service-ledger status reader |
| [JdbcIngestionLedger.java](JdbcIngestionLedger.java) | JDBC-backed implementation of the durable ingestion ledger |
| [JdbcObservationAdmissionReferenceStore.java](JdbcObservationAdmissionReferenceStore.java) | Service-DB recovery reference for managed-import observation admission |
| [JdbcPublishLedger.java](JdbcPublishLedger.java) | SQLite-backed per-slice/per-target publish ledger with CAS transitions |
| [JdbcRemoteFetchLedger.java](JdbcRemoteFetchLedger.java) | SQLite-backed idempotency ledger for read-only remote fetch |
| [JdbcRunLedger.java](JdbcRunLedger.java) | JDBC-backed durable run ledger for per-file ingest saga checkpoints |
| [LegacyLedgerImporter.java](LegacyLedgerImporter.java) | Imports the legacy file-backed ingestion ledger into the JDBC service ledger. The legacy file format stays at the adapter edge; durable checkpoints are replayed through the storage-neutral `IngestionLedger` port |

## Connection and transaction ownership

`SqliteDataSourceFactory` opens one Hikari pool with maximum capacity
`writeMax + readMax`; these settings do not themselves create physically separate
reader/writer pools. Bootstrap owns close and passes shared writer admission;
`JdbcSnapshotSliceReader` separately limits active snapshot readers. Physical
connection properties enforce foreign keys, synchronous/busy-timeout floors and
the selected cache/mmap/temp-store tuning. Persistent initialization requests
WAL, UTF-8 and incremental vacuum; suitability of the actual deployment remains
an operator concern.

`JdbcWriterAdmission` orders waiting control/expiry/slot/promotion/maintenance
operations using aging. It cannot preempt an active transaction, promise a
deadline or fence another process. Use the same admitted instance for one
canonical writer family; convenience constructors that create their own instance
do not supply shared production coordination automatically.

Native canonical writers acquire SQLite write ownership before effective time
and active-row decisions. They reserve non-reusable IDs independently before
canonical ownership, and roll back before restoring pooled auto-commit on
unsuccessful work. An explicit commit remains durable if later observation or
cleanup fails; receipts decide replay. This strong cleanup contract belongs to
those writers, not every helper: [OPS-15](../../../../../../../../../../../docs/KNOWN-ISSUES.md#ops-15) records the migrator's partial-DDL unchecked-failure path.

`JdbcStatementScope` caches at most 32 statements per thread-confined scope.
Leases clear parameters/batches, while the scope owns statement close. Match and
mutation sessions reuse the owning connection without committing/closing it;
callers own transaction boundaries, ID ranges, revision aggregation and receipt
publication. Close these sessions before commit. Receipt row sources may reuse
the consumer transaction connection, including with a one-connection pool.
Dynamic identifiers pass the adapter grammar; values use bindings. Do not turn
operator input into free SQL, even where DDL must compose identifiers/types.

## Canonical facts and projection/export identity

The compatibility writer handles lifecycle-disabled inserts and ordered field
mutation. Lifecycle confirmation owns one artifact transaction; managed import
owns one cross-artifact transaction and receipt. Active matching uses indexed
complete canonical key material and `_valid_until_epoch_ms > asOf`; hash equality
alone or retained history is not an active-row match. Ordered field origin uses
dataframe-owned registration rank/occurrence position, not worker completion time.
Admission timestamps and safe canonical UTC have different owners.

Public-row insertion/declared field changes advance artifact revision and mutable
projection work. TTL/provenance-only changes do not fabricate public mutations;
expiry advances projection generation independently. Projection acknowledgements
cover the installed generation, without clearing newer work. Repository streaming
holds one read transaction for rows/count/generation and must finish callbacks
before releasing it.

`JdbcExportSlotRegistry` stores sparse reusable assignments/free ranges per
profile/artifact. Canonical and lifecycle IDs remain non-reusable. Preferred-slot
requests stream through operation-local SQL scratch; validate the complete set
before allocation rather than reconciling independent chunks. Related-key
conflicts reject the complete logical fan-out; an unassigned survivor in an
initialized namespace is rejected rather than assigned a fabricated canonical ID.
TEMP cleanup explicitly targets `temp`, preserving same-named `main` tables.

`JdbcSnapshotSliceReader` owns its synchronous callback sequence and all read
cursors. It reconciles export slots before opening a profile read snapshot and
checks generation races. Id-less artifacts bypass slot allocation. The snapshot
owns coverage and rows; it does not publish files, commit service-ledger completion
or establish remote delivery.

## Workspaces and canonical receipts

`JdbcDocumentPreparationWorkspaceFactory` owns private source/policy pins and
aggregate lease/disk admission. Use one factory per root in a JVM: file locks
coordinate separate processes, not overlapping in-JVM accounting authorities.
Ownership markers, hashed observation directories and leases protect live/foreign
work from reaping. Disk accounting includes sidecars and retained pins; admission
estimates are reservations, not exact RSS measurement.

Document text uses quota-controlled UTF-16 spools and a fixed two-page cache;
indexed source scratch records overlap/occurrence evidence. Source scratch closes
before seal. The global disk reducer supplies sealed, repeatable prepared-row
cursors. Close workspaces/cursors and release leases on all outcomes; disposable
source scratch never becomes canonical commit authority.

`DocumentRowCodec` bounds UTF-8 row/field bytes and independently caps both values
and ordered-field-position maps at 128 entries. That structural cap is neither
the receipt batch size nor a generic CSV-column limit. Receipt row/position writes
flush in bounded 128-row/1-MiB batches. COMPLETE requires all expected artifact
markers (including zero-row artifacts) and consistent typed totals. Replay merges
two ordered cursors in one snapshot and rejects missing/duplicate/orphan field
positions. Compatible complete receipts remain canonical recovery evidence after
private source cleanup.

Import has a separate private schema and opaque per-delivery building/sealed
paths. Create/rebuild, sealing, metadata/count/hash verification and read-only
promotion attachment are distinct operations. The application pins delivery,
snapshot, contract and failure policy; the writer plans in TEMP tables and
commits accepted mutations, slots, artifact evidence, rejections/warnings and
`import_commit` together. Service delivery state is later finalized from that
receipt, without a transaction spanning both databases. Optional constructor
defaults are not the deployment's authoritative retention settings.

## Migration, health and coordination limits

The explicit [dataframe](../../../../../../../resources/com/iocextractor/adapter/out/store/jdbc/dataframe/README.md) and
[service](../../../../../../../resources/com/iocextractor/adapter/out/store/jdbc/service/README.md) catalogs evolve independently via `user_version`.
Configured artifact reconciliation separately creates/adds public columns and
internal lifecycle/history/typed receipt tables. Identity changes stage collision
checks before durable backfill. Upgrade history is required even where old
partition/aggregation tables are absent from the current runtime. No down-migration
or historical-SQL checksum enforcement is implemented by the normal migrator.

Service ledgers implement local CAS/idempotency facts; application schedules and
recovers them. Document/import admission references retain dataframe registration
identity without becoming the rank authority. Export terminal progress commits
with its ledger checkpoint. Fetch namespace isolation (OPS-11), nullable import
status handling (OPS-10) and ordered-field whitespace validation (OPS-14) remain
[open issues](../../../../../../../../../../../docs/KNOWN-ISSUES.md), not solved by table constraints.

`JdbcStorageHealthProbe` observes connection access, foreign keys, WAL, quick_check
and reported user_version. It returns `JdbcStorageHealth`, not an Actuator object.
Its healthy flag does not verify the expected migration target, every schema
object, lifecycle clock or all application checkpoint invariants. Bootstrap maps
that observation alongside separate readiness contributors.

See the [test package](../../../../../../../../test/java/com/iocextractor/adapter/out/store/jdbc/README.md) for local coverage and opt-in
limits. [Storage](../../../../../../../../../../../docs/dev/storage.md),
[canonical lifecycle](../../../../../../../../../../../docs/dev/canonical-record-lifecycle.md) and
[managed import](../../../../../../../../../../../docs/dev/dataframe-import.md) own cross-module algorithms,
recovery and policy. Exact operation/resource contracts remain in Java Javadoc.
