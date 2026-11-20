# Immutable artifact export

[Русская версия](ru/artifact-export.md)

Artifact export creates one verifiable immutable profile slice from canonical
SQLite data. Local slice availability and remote delivery are separate facts:
publish discovers only locally durable `COMPLETED` runs. Mutable generated CSV
files are not export inputs.

## Runtime flow

```text
IO-free profile validation at the CLI boundary
  -> canonical-data admission
  -> cross-process profile lease + incomplete-profile recovery
  -> revision/plan pre-gate
  -> export_run STARTED
  -> reconcile stable export slots at canonical generation G
  -> one consistent dataframe snapshot
  -> stream CSV + manifest into .staging
  -> durable _SUCCESS
  -> STAGED
  -> atomic staging-to-final rename
  -> AVAILABLE
  -> export_progress + COMPLETED
  -> SliceCompleted latency hint
```

`core/ioc-application` owns the plan, change policy and saga. JDBC supplies the
snapshot and ledgers; CSV materializes the filesystem slice; Jackson encodes the
manifest. Bootstrap composes CLI, daemon cadence, recovery, health and retention.
The CLI validates a profile before resolving lazy storage. A valid stateful
export passes common canonical-data admission before recovery, pre-gate or
snapshot work. Daemon cadence opens only after admission and startup recovery.

## Invariants

1. **A profile is an ordered, indivisible unit.** The supported output mode is
   `COMPLETE`; artifact and column order matter. Defaults keep `ioc_aggregate`
   in the separate `ioc-aggregate` profile, preserving `reputation-lists` and
   `address-blacklist` membership. Atomicity applies to one profile, not all
   configured profiles or mutable CSV files together.
2. **The plan identifies byte-affecting policy.** Artifact schema and identity,
   selected mapping and CSV layout contribute to hashes/fingerprints. Runtime
   timestamps and run IDs do not change plan identity. The original
   CRLF/ALL/ALL layout retains manifest v1; a different layout uses v2 and
   changes the plan hash. See [CSV format](csv-output-format.md).
3. **One snapshot owns coverage and rows.** One WAL read transaction captures
   revisions and rows for every artifact. With lifecycle active, one captured
   `asOf` applies to all active predicates; `valid_until == asOf` is excluded.
   Commits after that read snapshot opens belong to a later export. The boundary
   is fixed for the operation, not resampled for each row.
4. **Local publication is atomic.** CSV files, manifest and `_SUCCESS` are
   forced before the staging-to-final rename. Exact membership, hashes and
   marker are verified. The marker contains the manifest SHA-256 followed by
   LF; a marker alone is not proof of a valid complete slice. Unsupported
   atomic directory move fails without a copy fallback.
5. **Encoding is strict.** An unrepresentable character rejects materialization;
   lossy encoding would violate the byte/checksum contract. Partial staging may
   remain for inspection/recovery, but is not a final available slice.
6. **Change detection has two gates.** Ordered revisions and `planHash` first
   decide whether to materialize. A complete candidate is redundant only when
   its plan, per-artifact bytes and covered revisions match durable progress.
   A higher revision requires a new slice even when CSV bytes are identical.
   Revision can advance on insertion, reappearance or supported public-field
   mutation; it does not necessarily mean a new lifecycle.
7. **An event is not a commit.** `SliceCompleted` follows durable `COMPLETED`.
   Lost hints are recovered by publish reconcile. Neither an event nor an
   `AVAILABLE` directory alone establishes completed remote delivery.
8. **Expiry and TTL-only renewal do not create slices.** They do not advance
   public-data revision. A later public-data or plan change can admit an export
   that captures current active membership. Calling manual export with unchanged
   plan/revisions does not force a new expiry-only snapshot.
9. **An external ID is a scoped export slot.** With lifecycle active, artifacts
   with an `id` use stable reusable slots in `(profile, artifact)`. Canonical row
   and lifecycle identities remain separate and non-reusable. Id-less artifacts
   bypass allocation; disabled-compatible exports retain canonical IDs.

## Profile ownership and concurrency

[ADR-0037](../ADR/0037-profile-scoped-export-execution.md) scopes formation and
recovery by profile. The service DB allows one active run per profile, while a
NIO operation lease excludes simultaneous execution of that profile between
processes sharing the configured export root. The lease covers recovery and
the cheap pre-gate too. This is a local filesystem lock, not permission to run
multiple daemons against shared mutable paths.

Startup recovery visits incomplete profiles before cadence opens. Recovery
before formation touches only the leased profile. The CSV writer keeps
materialization state per invocation rather than holding a global streaming
monitor. Daemon execution uses two workers and at most one queued/running
attempt per configured profile; quiet/max-cap policy remains per profile.

`ioc.storage.dataframe.pool.read-max` bounds snapshot transactions. Clock/slot
transactions do not hold a reader permit; two simultaneous profiles require at
least two reader slots. Long reads still pin WAL checkpoint progress. A larger
pool does not create multiple SQLite writers. Stop the previous process and
preserve coordinated backups before an upgrade; an older binary may reject a
newer service schema even when TTL is disabled.

## Stable reusable export slots

`SnapshotSliceReader` is the application port for one complete snapshot. With
lifecycle `ACTIVE`, its JDBC implementation first performs a short transaction
in the dataframe DB:

1. Acquire write ownership, serializing with confirmation and expiry.
2. When first initializing a registry, seed
   `(profile, artifact, lifecycle_id) -> slot` from active canonical IDs to
   preserve the upgrade mapping. Preferred import-slot processing can also
   initialize that registry before the first export.
3. Release vanished assignments, coalesce free ranges, assign the lowest
   positive holes to new lifecycles, then allocate from `next_slot`.
4. Record `artifact_projection_state.required_generation` as registry
   `source_generation`.

A separate read transaction then validates generation and complete assignments
before the first callback. A race between reconciliation and snapshot opening
retries at most three times; persistent churn fails instead of streaming mixed
mapping. Rows expose `export_slot AS id` in slot order. Covering assignment and
unique lifecycle indexes support the stream. Manifest coverage `upperId` is the
maximum active slot for such an artifact, not a canonical ID or allocator
high-water mark.

Slots live with canonical rows in `ioc-dataframe.db`, so preferred import-slot
requests can share the owning canonical transaction. Expiry/history do not
renumber surviving slots. Historical slices preserve their original mapping;
the same numeric slot can identify a different lifecycle in another slice.
Diagnose it together with slice, profile and artifact.

## Durable state and recovery

```text
STARTED -> STAGED -> AVAILABLE -> COMPLETED
   |          |          |
   +-------> FAILED <-----+
STARTED -> SKIPPED
```

A pre-gate skip creates no run row or slice and returns no run ID. A redundant
materialized candidate is discarded and gets durable `SKIPPED`, retaining the
previous published slice in progress. Neither path emits `SliceCompleted`.

Formation errors may leave the last durable active checkpoint, commonly
`STARTED`; they do not necessarily write `FAILED` immediately. The next owned
recovery inspects ledger, staging/final files and manifest coverage, without
reading current canonical rows:

| Evidence/checkpoint | Recovery |
|---|---|
| `STARTED` with missing/partial staging | Remove incomplete staging and mark `FAILED` |
| `STARTED` with valid candidate | Repeat redundancy decision; discard as `SKIPPED` or finish the marker and advance to `STAGED` |
| `STAGED` with valid staging or matching final directory | Complete atomic availability idempotently |
| `AVAILABLE` with valid final directory | Reconstruct progress from its manifest and finish `COMPLETED` |
| Corrupt/conflicting or incompatible durable evidence | Mark `FAILED` with `EXPORT.RECOVERY_FAILED`; do not overwrite it with a fresh snapshot |
| Recovery I/O/runtime failure | Report the failure; the durable checkpoint can remain incomplete for a later owned retry |

Progress and `COMPLETED` commit together in the service DB. Filesystem and DB
are not one transaction; checkpoint ordering and inspection close the crash
windows. A normal-path event publisher failure can reach the caller after
completion; recovery suppresses that delivery failure. Both retain the completed
slice and rely on publish reconcile. A failed command is therefore not proof
that no durable slice exists.

## Cadence and retention

CLI selects a configured profile with `ioc export --profile reputation-lists`.
It does not parse a document or bypass change detection. Daemon interval or
quiet-period/max-cap scheduling consumes committed public-data hints; periodic
checks remain the backstop. Import emits one artifact-level hint after its
whole transaction, not one per row. TTL-only confirmation and import no-op do
not create a new revision. A changed aggregate `name` is a public mutation;
origin-only metadata with unchanged `name` is not.

Stopping the scheduler cancels new checks and terminates both timer and profile
workers. Failure stopping one executor does not skip the other; later failures
are suppressed. Executor references remain until termination is confirmed,
preventing unsafe restart or a late admission reopening a stopped scheduler.

Retention treats each final slice directory as one unit and applies age/count
per profile. With remote publish enabled, `PublishLedgerSliceRetentionGuard`
blocks deletion until every configured `slice × target` pair has a ledger record
in `SUCCEEDED` or explicitly `ABANDONED`. Missing records and retryable work
keep the slice pinned. The guard runs immediately before deletion, making
`max-count` best effort rather than permission to lose undelivered data.

Do not manually alter completed slice files or substitute mutable CSV files.
A lost historical slice requires restoration of its original verified bytes;
current truth can produce a new slice, not reproduce that historical identity.

## Extension and sources of truth

A new profile uses enabled configured artifacts and their identity contracts.
A new output mode requires explicit application/writer semantics; unknown modes
must not fall back to `COMPLETE`. A new manifest codec belongs behind
`SliceManifestCodec`. Remote transport belongs to sync/publish.

- Model/saga: [export package](../../core/ioc-application/src/main/java/com/iocextractor/application/export/README.md), [ExportService](../../core/ioc-application/src/main/java/com/iocextractor/application/export/ExportService.java).
- Gates/recovery: [ExportChangeDetectorTest](../../core/ioc-application/src/test/java/com/iocextractor/application/export/ExportChangeDetectorTest.java), [ExportRunRecoveryServiceTest](../../core/ioc-application/src/test/java/com/iocextractor/application/export/ExportRunRecoveryServiceTest.java).
- Snapshot/slots: [JdbcSnapshotSliceReader](../../adapters/adapter-store-jdbc/src/main/java/com/iocextractor/adapter/out/store/jdbc/JdbcSnapshotSliceReader.java), [ReusableExportSlotIntegrationIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/ReusableExportSlotIntegrationIT.java).
- Filesystem protocol: [CsvArtifactSliceWriterIT](../../adapters/adapter-csv/src/test/java/com/iocextractor/adapter/out/sink/csv/CsvArtifactSliceWriterIT.java).
- Assembly/cadence: [AppConfig](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/AppConfig.java), [DaemonExportSchedulerTest](../../bootstrap/ioc-app/src/test/java/com/iocextractor/bootstrap/DaemonExportSchedulerTest.java).
- Whole composition: [OnDemandExportIntegrationIT](../../bootstrap/ioc-app/src/test/java/com/iocextractor/OnDemandExportIntegrationIT.java).

## When to update this document

Update when state transitions, snapshot/atomic availability, plan/hash policy,
slot visibility, recovery evidence, cadence or retention ownership changes.

## Related documents

- [Storage](storage.md) — revisions and transaction authority.
- [Canonical lifecycle](canonical-record-lifecycle.md) — active membership.
- [Sync](sync.md) — completed-slice delivery.
- [Event coordination](event-coordination.md) — hints and reconcile.
- [Dataframe import](dataframe-import.md) — public mutations and slot requests.
- [ADR-0012](../ADR/0012-streaming-dataframe-emission.md) — artifact emission.
- [ADR-0021](../ADR/0021-stable-reusable-export-slots.md) — reusable slots.
- [ADR-0022](../ADR/0022-revision-significant-identical-export.md) — revision-significant identical bytes.
