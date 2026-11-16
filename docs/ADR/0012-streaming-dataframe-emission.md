> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0012-streaming-dataframe-emission.md) (relative link addresses rebased on relocation).

<a id="0012--эмиссия-и-экспорт-датафреймов-поверх-бд-truth-стрим"></a>
# 0012 — Dataframe emission and export over DB truth (streaming)

<a id="статус"></a>
## Status

**Implemented — C0–C11 closed.** Findings F1–F5 and ref-1…9 are implemented:
strict WAL snapshot, callback-streaming JDBC→CSV, atomic `artifact_revision`,
CAS-backed export saga/recovery, deterministic manifest + `_SUCCESS`, CLI/daemon cadence,
health and delivery-aware slice retention. The final verification covers concurrent ingest,
duplicate/`SKIPPED`, crash windows, fresh/upgrade migrations, a golden slice and architectural
boundaries. This document remains a design log and implementation trace for the next stage,
[0011](0011-remote-sync.md), which consumes completed immutable profile slices.

Cross-cutting over the **implemented** storage layer (ING-4): touches canonical write
revision, JDBC snapshot, CSV/filesystem writer, service ledger and bootstrap lifecycle.
**Rewritten** after SQLite storage landed and the β-collapse removed partitions and the
separate aggregation pass. The former version assumed “overwriting the canonical file”,
which is no longer true. Grounded in current code,
[../ingestion.md](../dev/ingestion.md) and [../ARCHITECTURE.md](../ARCHITECTURE.md).

<a id="контекст"></a>
## Context

The source (SMB share/directory) receives files continuously; the application runs as a stream.
The original question — “how to form a dataframe over an unbounded stream when no complete
set exists” — is **resolved by storage**. Checked against code (current reality):

- **SQLite is the system of record.** Both oneshot and daemon write directly to canonical
  storage (`JdbcIocSink` per source), deduplicating with
  `INSERT … ON CONFLICT(row_key) DO NOTHING` (keep-first). No partitions or separate
  aggregation remain; `AggregationService` disappeared into `IngestionService`.
- **CSV (`*_generated.csv`) is an atomic DB projection** (`CsvArtifactProjection`:
  `SELECT → temp → ATOMIC_MOVE`), not the system of record.
- **Projection is per-file and synchronous within the ingest saga**: after DB commit,
  `IngestionService` calls `projection.project(name)` for each affected artifact →
  `markProjectionCompleted` (run-ledger `IngestRun`, phases
  `DB_COMMITTED ↔ PROJECTION_COMPLETED`).
- IDs are per-artifact `AUTOINCREMENT` + `row_key TEXT UNIQUE`; provenance/observation
  is `<artifact>_sources` (1:N: `first_seen_at`, `last_seen_at`, `occurrences`).
- The export saga is implemented over service migration `v5__export_state.sql`:
  `export_run` + `export_progress`, global single-flight, forward recovery and an operational read model.

The implemented task was narrow and concrete: **emission cadence** and the **export contract
for remote consumers**. Industry models apply to export, rather than a “growing file”:

- **Dataflow model** (Akidau et al., VLDB 2015; *Streaming Systems*; Apache Beam):
  *what / where (window) / when (trigger+watermark) / how (accumulating vs discarding)*.
- Spark Structured Streaming **output modes**: `complete` / `append` / `update`.
- `_SUCCESS` readiness marker (Hadoop/Spark `FileOutputCommitter`); immutable-file
  publication in a data lake.
- Kappa (Jay Kreps), if both increments and periodic complete lists become necessary.

<a id="решения"></a>
## Decisions

**1. Forming a dataframe over a stream is solved by storage and is no longer an open
question.** Continuous bounded **UPSERT** into DB truth; the DB always contains a consistent
complete set. A consistent snapshot is one SQLite read transaction under **WAL** (one or
more `SELECT`s within one snapshot; writes do not block reads). No need to “wait for the
complete set”: completeness means **as-of current DB state**. (The former “empty ledger”
barrier and fear of “torn copy of a growing file” do not exist in this model.)

**2. Two different emissions — keep them distinct (the key separation).**
- **Local projection** `*_generated.csv`: **mutable, always-fresh complete DB snapshot**
  for local consumers, atomic (temp→rename), part of the ingest saga. Remains unchanged.
- **Export for delivery**: timestamped **immutable** slices (snapshot or increment) with
  a readiness marker for remote consumers ([0011](0011-remote-sync.md)). A separate
  concern, the **export saga**, owns an export-run ledger. This is the clean delivery
  bridge: local projection for ourselves, export for the outside.

**3. Export contract — first-class output-mode** (Spark vocabulary):
- `complete`: periodic **complete snapshot** (`SELECT … ORDER BY id`); consumer fetches
  the entire current list;
- `append`: **increment** since the previous watermark (`WHERE id > lastExportedId`).

**V1 implements only `complete`** (F4): `append` is a designed extension point, **not built
in v1**; configuration value `append` **fails fast** (`EXPORT.UNSUPPORTED_MODE`, FATAL),
without silently falling back to complete. Delivery ([0011]) is mode-**agnostic**.
The complete-default rationale and deferred append scope are in §3a below.

**4. An export slice is self-describing (format below).** The published unit is an
**immutable directory of files for one export profile**,
`var/export/<profile>/<ts>__<run_id>/`. The **directory** carries identity
(`<profile>/<UTC-ts>__<run_id>`, ref-2; stable data filenames, no per-file timestamps).
**`manifest.json`** describes per-artifact coverage/rows/sha256/identity-epoch (ref-3).
**`_SUCCESS`** is written last and holds the manifest hash (ref-4); consumers take slices
only when the marker exists. The `_SUCCESS` → manifest → files trust chain closes
consumer torn reads at the contract level. Layout and materialization flow: “Slice format”.

**5. Dedup / IDs for increments — DB ID state; separate watermark state.**
Per-artifact DB `id` + `row_key` provide keep-first and monotonic IDs out of the box:
increments need **no ID-assignment state** (storage resolves the former “assign IDs under
append” question). **This does NOT make append stateless:** it needs persistent
**watermark/progress state**, `lastExportedId` per export profile. Each consumer's delivery
progress belongs separately to 0011 `publish_ledger`. That state and its recovery make
`append` a separate deferred iteration (F4), rather than a free option.

**6. Export saga is formation-only; delivery has its own saga (F3).** Like the ingest
saga, without 2PC: forward recovery, idempotent steps, export-run-ledger checkpoints
(`STARTED → STAGED → AVAILABLE → COMPLETED`; + `FAILED`/`SKIPPED`, ref-7).
**`export_run` concerns formation only**; terminal `COMPLETED` means a local slice with
`_SUCCESS`. **No `PUBLISHED` in `export_run`.** The delivery boundary is the
**filesystem** (`var/export/<profile>/<slice>/` + `_SUCCESS` + manifest), not a shared table.
Delivery ([0011]) owns a **separate `publish_ledger` keyed by (slice_id, target_id)**
(independent fan-out of one slice to N targets); transport **does not touch `export_run`**.
Correlation uses manifest `slice_id`. The run-ledger pattern already exists
(`IngestRun`/`JdbcRunLedger`); export mirrors it (new table + `JdbcExportRunLedger`).
(Recovery is **forward from files, without DB recomputation**; F1/F5/ref-5.) Details:
“Implementation architecture”.

<a id="3a-дефолт-output-mode--complete-закрывает-q1-и-q3"></a>
### 3a. Default output-mode — `complete` (closes Q1 and Q3)

**Decision.** V1 export mode is **`complete`**, a full snapshot. `append` is a designed
extension point for a narrow scenario (high flow + expensive/metered link + a consumer
maintaining its own accumulating state without needing deletion visibility). It is **not
built in v1** and **fails fast** in configuration (F4); its watermark/delivery state
(decision 5) makes it a separate iteration. **Q3 (late data) consequently disappears**:
`complete` accumulates, every snapshot reflects current DB state, and a row cannot be
“already shipped and left behind”.

**Underlying principle.** Default to a mode correct under the **weakest assumptions**;
make optimization opt-in. Export mode does not determine data mutability: the data itself
does. `complete` **tolerates** mutation; `append` **assumes** row immutability. The contract
must not bet on an assumption not guaranteed over the project's future. Future processing
(enrichment, resolvers) fits into ETL **before** truth writes, but its impact on row lifecycle
and future datasets is unknown. The decision is **reversible**: enable `append` per export
profile when a concrete delta consumer appears (YAGNI; default to what is always correct).

**Mechanism.** `complete` = one **WAL** read transaction reading configured artifacts
sequentially (`SELECT … ORDER BY id`) from a common snapshot → streaming serialization →
immutable directory publication (`profile/UTC-ts__run-id` + checksum + `_SUCCESS`) by the
export saga. No per-target watermark state is **required**, unlike append's persistent
`lastExportedId` and recovery. The slice describes itself (as-of coverage stamp
`max(id)` / `_created_at ≤ T`).

**Benefits.**
- **Deletion correctness (decisive).** IOC removal (TTL, false-positive withdrawal,
  reaper) is reflected automatically: the row is absent from the next snapshot.
  `append` **cannot express deletion** (only adds), leaving removed IOCs in consumer
  state forever. For blacklists with routine removal, this is a **correctness** argument.
- **State freshness.** Accumulating mode exposes subsequent row updates (future enrich,
  `_sources` aggregates) in every snapshot; discarding append ships creation-time state.
- **Simplicity.** No per-target watermark state/recovery; self-contained slices.
- **Alignment with code.** Local projection already follows `complete` (full artifact
  reprojection); export simply publishes immutable snapshots without new conceptual machinery.
- **Simple retention.** Each complete slice is the whole truth, so retain a minimum
  (even 1). Append is a **chain**: losing a link breaks reconstruction, requiring the
  whole chain or periodic full checkpoints (a slide into a Lambda/Kappa hybrid).

**Complete risks and mitigations.**
- **O(N) per trigger, growing with the whole table**, rather than delta (monotonically
  for a growing-only blacklist). Mitigate through **cadence/trigger (Q4)**, not mode changes.
- **Delivery transfer amplification ([0011]).** SMB publish transfers the whole growing
  list each cycle: the **only** case where append objectively wins (narrow/metered link),
  hence the condition for opt-in append.
- **WAL under a long reader.** Full `SELECT` holds a read transaction; a large table +
  frequent ingestion can grow WAL/impede checkpoints. Operational, not correctness,
  risk: `wal_autocheckpoint` + reasonable cadence.
- **Retained slice size.** Full copy per snapshot, offset by self-containment and low retention count.

**Foundations.** Spark Structured Streaming modes (`complete`/`append`/`update`; reject
update because a flat reputation-list consumer does no keyed merge); Dataflow
*what/where/when/how* (complete = accumulating, append = discarding on *how*;
*where*/window is trivial: the entire table as-of-now); table/stream duality (Kreps):
DB is the table side, export is the reverse `table → stream` boundary; `_SUCCESS`
(Hadoop/Spark `FileOutputCommitter`).

<a id="4a-триггер-экспорта--конфиг-стратегия-дефолт-interval-закрывает-q4"></a>
### 4a. Export trigger — configurable strategy, default `interval` (closes Q4)

**Decision.** Export trigger is a **declarative configuration-selected strategy**
(registry like refang rules / match policy / value providers), not a fixed mechanism:
`ioc.export.trigger.type: interval | quiet-period`.

- **`interval` (processing-time) is default.** A full snapshot every N: cheap,
  predictable, bounds complete cost **regardless of input pattern**, naturally coalesces
  changes. Always safe: the snapshot is consistent as-of; if a tick catches the middle
  of a batch, the next takes the rest.
- **`quiet-period` (debounce) is opt-in for bursty input.** Snapshot after **T of canonical
  truth inactivity** (`artifact_revision.changed_at`): one fresh snapshot per batch,
  without reshooting mid-upload. File arrival alone does not reset the timer:
  duplicates that do not change truth are not export activity. **A max-cap is mandatory**
  (“at least once per M regardless of activity”); otherwise overlapping sources can
  prevent silence and starve export.
- **Do NOT build `on-new-rows` (every commit) now.** Under complete, O(N) per commit
  amplifies work. Add later through OCP if a delta consumer appears (opt-in append, §3a).

**The trigger reacts to canonical changes, not input (ref-6/7).** Quiet-period debounces
truth changes, not file arrivals; interval checks changes too. Change detection
(`artifact_revision` pre-gate + content-hash skip) removes empty firings; see “Change
detection / skip”. Canonical writes must report **actual** inserts (sum of
`executeUpdate()`, not prepared count under `ON CONFLICT DO NOTHING`).

**Separation to retain.** N/T/M are **operator configuration**, adjusted operationally.
The **strategy set** and **default** are architectural decisions (an operator cannot
configure an absent mechanism); defaults define out-of-box behavior for untuned deployments.

**Parallel with Q1 (§3a).** Same pattern: safe default + narrow-case opt-in.
`complete`/`interval` is v1 out of the box; quiet-period independently opts into a
bursty-input trigger. Append remains a future output mode, independent of trigger selection.

**Input context behind this choice.** Sources are bursty/discrete: document batches now,
planned website-list parsing (upstreams update on schedules), permanent file-import support.
No truly continuous stream is expected; ultra-low latency is unnecessary, and
interval/quiet-period fit better than on-new-rows.

**Foundations.** Spark `Trigger.ProcessingTime` (microbatch interval) /
`Trigger.Continuous`; Beam/Flink session windows (quiet-period/debounce);
Kafka `linger.ms` + ceiling and Beam early firings as max-cap examples.

<a id="следствия"></a>
## Consequences

- **Cadence is the main remaining lever.** Local projection currently synchronously
  regenerates the **whole** affected artifact CSV per file (O(rows), within ingest).
  This is the high-flow cost; decoupled cadence (coalescing/interval emitter) belongs
  here, with an existing seam (`DB_COMMITTED ↔ PROJECTION_COMPLETED` separated by saga).
- Separate `ioc.export` configuration: named profiles (artifact sets), output-mode,
  trigger, storage root, format and retention. `ioc.sync.publish.targets` references
  profiles without controlling formation.
- The export saga owns **its own** export-run ledger/progress; tables come from forward
  migration `v5__export_state.sql` (v4 removed the former `export_run` placeholder).
- Delivery ([0011]) reads export results; output-mode is determined **here**.
- `ioc health`/diagnostics expose revision lag / age of the latest successful slice.
- Slice-level retention cleans local immutable slices; destination retention belongs
  to delivery/the recipient ([0011](0011-remote-sync.md)).

<a id="архитектура-реализации"></a>
## Implementation architecture

The export saga is a **structural twin of implemented ingestion**; mirror its proven
pattern (`IngestionService` / `IngestRun` / `RunLedger` / `IngestRunRecoveryService`).
Bounded context: **Artifact Emission**, separate from Ingestion but sharing canonical
truth. Two sub-capabilities (decision 2): local projection (`*_generated.csv`, existing,
reused) and export for delivery (new).

<a id="ubiquitous-language"></a>
### Ubiquitous language
- **Export Run:** one export-saga instance (twin of *Ingest Run*).
- **Export Slice:** published unit, an immutable directory + checksum + `_SUCCESS` +
  manifest containing coverage stamps.
- **Coverage / Watermark:** per-artifact as-of stamp (`revision`, `changed_at`, `max(id)`),
  captured in the same SQLite snapshot as serialized rows. Provenance, not a recovery reread key.
- **Emitter / Cadence:** generalized trigger timing/firing; guardrail Q2 lives here:
  a general abstraction reused by ING-7 for local projection.

<a id="сервисы-и-порты-r-reuse--n-new"></a>
### Services and ports (`[R]` reuse · `[N]` new)

| Role | Ingestion (existing) | Export |
|---|---|---|
| In-port (use-case) | `IngestSourceUseCase` | `[N] ExportArtifactsUseCase`, `[N] RecoverExportUseCase` |
| Orchestrator | `IngestionService` | `[N] ExportService` (`application/export`) |
| Recovery | `IngestRunRecoveryService` | `[N] ExportRunRecoveryService` |
| Run model | `IngestRun`/`IngestRunStatus` | `[N] ExportRun`/`ExportRunStatus`/`ExportCoverage` |
| Run-ledger (out) | `RunLedger` | `[N] ExportRunLedger` (separate port, choice A; **CAS transitions + DB-backed single-flight**, ref-5; retrofit CAS into ingest `JdbcRunLedger`) |
| Result write (out) | `ArtifactProjection` | `[N] ArtifactSliceWriter` |
| Truth read (out) | `CanonicalArtifactRepository` | `[N] SnapshotSliceReader`: callback-streaming into `ArtifactSliceWriter` **within one read-tx across all profile artifacts** + per-artifact coverage (F5); `load(name)` remains for local projection |
| Append watermark (out) | — | `[N] ExportWatermarkStore`, **deferred with append (F4)**: per-profile/artifact `lastExportedId`; delivery progress stays in 0011 `publish_ledger`; not built in v1 |
| Change/revision | — | `[N] ArtifactRevisionReader` + dataframe DB `artifact_revision`; revision changes atomically with canonical write |
| Export progress | — | `[N] ExportProgressStore` in service DB: per-profile/artifact `last_revision`/hash/slice/plan-hash |
| Retention | `RetentionPolicy`/scheduler | `[R]` policy/scheduler + `[N] SliceRetentionStore`, `[N] SliceRetentionGuard` (Q5/F2) |
| Observability | diagnostics/ECS | `[R]` infrastructure + `[N] EXPORT category/events/health revision lag |

Small focused services: `ExportService` orchestrates, `ExportRunRecoveryService` recovers
forward from staging/final, `ExportChangeDetector` handles pre-gate/post-hash. No separate
`ExportCoverageResolver`: independent `max(id)` would break the strict snapshot; only
`SnapshotSliceReader` returns coverage from its read transaction.

<a id="размещение-по-модулям-границы-держатся"></a>
### Module placement (boundaries retained)

| Layer / module | Additions |
|---|---|
| **core/ioc-application** | `application/export` (services, in/out ports, `ExportProfile`/`ExportPlan`/manifest model), framework-free; **do not touch domain** (`ExportRun` is application-level like `IngestRun`) |
| **adapter-store-jdbc** | `[N] JdbcSnapshotSliceReader`, `JdbcArtifactRevisionReader`, `JdbcExportRunLedger`/`JdbcExportProgressStore`; dataframe migration `v3__artifact_revision.sql`; service migration `v5__export_state.sql`; reuse Hikari/JdbcClient/migrator |
| **adapter-sink-csv** | `[N] CsvArtifactSliceWriter`: immutable `<profile>/<ts>__<run_id>` directory; callback-streaming (`ArtifactRow` → `CSVPrinter` → `DigestOutputStream`, single-pass SHA-256+rows, constant memory), staging/atomic rename |
| **adapter-manifest-json-jackson** | `[N] SliceManifestCodec`: sole owner of Jackson JSON serialization/parsing; preserves one integration-library family per adapter and is reused by 0011 |
| **driving lifecycle (bootstrap)** | `[N] DaemonExportScheduler` (`SmartLifecycle`, following `DaemonMaintenanceScheduler`, `@ConditionalOnProperty mode=daemon`) invokes `ExportArtifactsUseCase`; cadence is **framework-free `CadenceSource`** (`interval`/`quiet-period`+max-cap, choice B) |
| **adapter-cli-picocli** | `[N] ioc export` command (manual/on-demand, like `extract`) |
| **bootstrap/ioc-app** | `[N] ioc.export` config (profiles + trigger/retention) and composition; service-storage/export graph is `@Lazy`, not restricted to `mode=daemon`: daemon instantiates it through dependencies, `ExportCommand` only on invocation; ordinary extract/health do not open service DB (ref-8) |

The local slice directory (`var/export/<profile>/…`) is the **0011 boundary**: a target
references an export profile and takes the **whole** slice with `_SUCCESS` byte-for-byte,
without per-target file filters. Manifest validity and transport-agnostic 0012 are preserved.

<a id="сага-recovery-консистентность-f1f5ref-5"></a>
### Saga, recovery, consistency (F1/F5/ref-5)

**Written files define a slice; rereading the DB does not.** Read DB **exactly once** per
run. After verifiable staging, files fix the content; recovery **never rereads the DB**,
which is mutable (updates/deletions under the same IDs would produce a different slice
under the same immutable name).

`ExportRun` checkpoints (formation-only, F3):
```
STARTED → STAGED → AVAILABLE → COMPLETED
    └──────────────→ SKIPPED                 (+ FAILED из любой незавершённой фазы)
```

- `STARTED`: run/slice-name/profile recorded, single-flight acquired; staging can be empty/partial.
- `STAGED`: data + manifest + `_SUCCESS` closed; `force(true)` on files, staging directory
  and parent; all hashes checked. Durable checkpoint for internal files.
- `AVAILABLE`: staging **atomically** renamed to final directory, the sole local publication
  point. Staging shares the `var/export` filesystem; unsupported `ATOMIC_MOVE` fails fast
  (`EXPORT.ATOMIC_PUBLISH_UNSUPPORTED`), without copy fallback.
- `COMPLETED`: one service-DB transaction applies `ExportProgress` and CAS
  `AVAILABLE→COMPLETED`. Publish can use `AVAILABLE`; completion closes bookkeeping.
- `SKIPPED`: candidate byte-identical to previous slice; remove staging but atomically advance
  snapshot revisions in `ExportProgress`, preventing rematerialization next tick.

**Crash matrix / forward recovery (never reread DB truth):**

| Observed state | Recovery |
|---|---|
| `STARTED`, staging lacks valid manifest | Delete staging, `FAILED`; next run uses a new snapshot |
| `STARTED`, valid manifest+data, no `_SUCCESS` | Repeat post-hash; byte-identical → delete staging and atomically `SKIPPED`; otherwise verify hashes, write+force `_SUCCESS`, fsync directory, CAS→`STAGED` |
| `STARTED`, complete valid staging with `_SUCCESS` | Repeat post-hash against service `ExportProgress`; byte-identical → delete staging and atomically `SKIPPED`; otherwise CAS→`STAGED` |
| `STAGED`, staging exists | Verify root hash, atomic staging→final rename, fsync published parent, CAS→`AVAILABLE` |
| `STAGED`, final exists, staging absent | Verify final through `_SUCCESS`, CAS→`AVAILABLE` (crash after rename) |
| `AVAILABLE` | Idempotently apply manifest progress and CAS→`COMPLETED` in one service-DB transaction |
| staging/final conflict with manifest or both exist | `FAILED`, corruption diagnostic; never automatically overwrite immutable final |

Durability baseline targets Linux/systemd deployment, including `FileChannel.force(true)`
for files and directory fsync. If filesystem/JDK cannot fsync directories, startup preflight
explicitly warns and downgrades to process-crash guarantees; never silently simulate durability.

**Strict multi-artifact consistency (F5).** Materialization reads **all slice artifacts in
ONE SQLite read transaction** (WAL snapshot isolation: all tables as-of one point in one
DB file), without “masks@N + hashes@N-1”. New `SnapshotSliceReader` uses one connection/tx,
all artifacts + per-artifact `revision`/`changed_at`/`max(id)` coverage, **separately from**
`load(name)` (per-artifact local projection). Reject a global export↔ingest lock
(publish does not block pipeline, 0011 decision 11). Trade-off: longer snapshot retention
prevents WAL checkpointing during the read (ref-1 streaming-vs-heap).

**Cross-adapter streaming contract (ref-1+F5).** Application port
`SnapshotSliceReader.stream(SnapshotRequest, SnapshotRowConsumer)` is synchronous/callback-based:

- `JdbcSnapshotSliceReader` owns connection/read-tx/cursor; reads coverage for all profile
  artifacts in that snapshot, then calls consumer `begin(snapshotMeta)`,
  `beginArtifact(meta)`, `row(ArtifactRow)`, `endArtifact()`;
- `CsvArtifactSliceWriter` implements consumer, owns staging files/CSV/digest, sees no JDBC
  types and retains no `ArtifactRow` after callback return;
- callbacks cannot be async or close JDBC resources; writer exceptions escape `stream`,
  close cursor/transaction, and leave `STARTED` for crash-matrix handling;
- `ExportService` orchestrates without reading rows/managing IO. Transaction lifecycle
  remains in JDBC, serialization in CSV, heap O(1).

**Coverage is metadata, not a recomputation key.** Per-artifact
`revision`/`changed_at`/`upperId` (+ identity epoch/hash) in the manifest supports
provenance/lag/future append; it is **not replayed** during file-based recovery.

**Ledger correctness (ref-5):**
- **CAS every transition**: `UPDATE … SET status=NEXT WHERE run_id=? AND status=<prev>`;
  require `affected==1`. On `0`, reread: same/later permitted status means idempotent
  replay; incompatible/terminal status means diagnostic+exception, not silent no-op.
  **Also retrofit existing ingest `JdbcRunLedger`.**
- **Single-flight**: globally at most one active export run, **DB-backed** partial unique
  index over `STARTED|STAGED|AVAILABLE`; crashes block new runs until recovery. Daemon
  recovery is created before the `SmartLifecycle` scheduler; `ioc export` always invokes
  `RecoverExportUseCase` before attempting a run.
- **Recovery ownership**: recovery + formation hold one local OS file lease under export
  root. The DB row remains durable single-flight authority; the lease prevents a second
  live CLI/daemon process treating owned staging as crash evidence. OS releases the
  lock after process crash, letting the next process recover forward.

`v5__export_state.sql` creates `export_run(run_id, profile, status, slice_name, plan_hash,
manifest_sha256, started_at, updated_at, reason)`, a partial active-status unique index,
and `export_progress` with PK `(profile, artifact)`. Fill `manifest_sha256` at `STAGED`;
paths are not absolute: derive staging/final deterministically from root and slice name.

**Mechanics:** staging `var/export/.staging/<run_id>/`, final
`var/export/<profile>/<UTC-ts>__<run_id>/`; names fixed at `STARTED`. Published area hides
incomplete work. After an early crash, a new run has a new `run_id`; only recovery for
that ledger row deletes stale staging, never a general opportunistic cleaner.

Deferred `append` (F4) will need `ExportWatermarkStore`; retain the seam.

<a id="формат-среза-ref-1234"></a>
### Slice format (ref 1/2/3/4)

Disk layout (profile `reputation-lists`):
```
var/export/reputation-lists/<UTC-ts>__<run_id>/
  ├── masks_list.csv
  ├── ip_list.csv
  ├── hashes_list.csv
  ├── manifest.json                # самоописание (ref-3)
  └── _SUCCESS                     # commit-маркер последним, держит hash(manifest) (ref-4)
```

- **Identity (ref-2):** `slice_id = run_id`; directory `<profile>/<UTC-ts>__<run_id>`.
  Profile defines the indivisible set, timestamp helps sorting/readability, run ID
  prevents manual/scheduled collisions. No per-file timestamps.
- **`manifest.json` (ref-3):** `manifest_version`, `slice_id`/`run_id`, `profile`,
  `created_at`, `output_mode`, `format` (csv: charset/delimiter/quote/null),
  `artifacts[]` with `file` / `rows` / `coverage.revision`/`changed_at`/`upper_id` /
  `identity_epoch` / `identity_hash` / `schema_hash` / `sha256`. Compute `schema_hash`
  deterministically from ordered public columns + declared types: per-artifact
  contract, unlike global SQLite `user_version`. Identity epoch/hash and schema hash
  make identity/schema discontinuities explicit. JSON belongs to the separate Jackson
  adapter's `SliceManifestCodec`, without a hand-written JSON writer.
- **`_SUCCESS` (ref-4):** written **last**, contains **manifest SHA-256**; trust chain:
  `_SUCCESS` → manifest → per-file `sha256` → data. Consumer/recovery share one root.
- **Streaming reader (ref-1):** JDBC cursor/callback → `CSVPrinter` → `DigestOutputStream`
  → staging file; **constant memory** as lists grow. SHA-256/row-count in the same pass
  (free for ref-3/4). WAL trade-off as F5: one read-tx, mitigated by `wal_autocheckpoint`
  + cadence; ingest unblocked. Leave local `CsvArtifactProjection` heap materialization
  to ING-7.

- **At-rest transforms (ref-9):** future compression/encryption is a **formation option**,
  not v1. When activated, slices become `.gz`/`.enc`; manifest names algorithm/key-id
  and covers final bytes. Publish remains byte-preserving. SMB3 encryption/protocol
  compression is transparent and preserves end-to-end bytes.

**Materialization flow:** one read-tx → coverage+per-artifact callback→CSV→digest→staging →
`manifest.json` → SHA-256(manifest) → **`_SUCCESS` last** → fsync → `STAGED` → atomic rename →
`AVAILABLE` → service-DB transaction(progress+`COMPLETED`).

<a id="обнаружение-изменений--skip-ref-67"></a>
### Change detection / skip (ref-6/7)

A trigger fires a *candidate*; change detection decides whether to publish, avoiding
empty identical slices.

- **ref-6: canonical write reports actual changes.**
  `CanonicalArtifactRepository.write` becomes `CanonicalWriteResult(inserted, revision)`:
  inserted sums `executeUpdate()` for public artifact rows under `ON CONFLICT DO NOTHING`,
  not prepared count or connection-global `changes()`. `JdbcIocSink.write()` returns
  inserted, also correcting `ioc.rows`.
- **Atomic change truth: dataframe `artifact_revision`.** Dataframe v3 creates
  `{artifact PK, revision, changed_at}`. In the **same JDBC write transaction** when
  `inserted>0`, execute `revision=revision+1, changed_at=now`; a crash cannot separate
  canonical rows from revision. Oneshot and daemon share this repository.
- **Cheap pre-gate.** Compare profile revisions and `plan_hash` against `ExportProgress`.
  Plan hash covers ordered artifact set, schema/identity hashes, CSV format, active
  transforms. No changes → no run. Config/schema changes cannot be hidden by unchanged revision.
- **Snapshot authority.** Once started, reread revision/changed_at **in the same read-tx**
  as rows; put precisely those values in the manifest. A later commit bumps revision
  for the next tick; progress never records a revision unseen by its snapshot.
- **Authoritative content-hash post-check.** Compare per-artifact SHA-256 + plan hash after
  materialization. All equal → `SKIPPED`: delete staging, advance snapshot revisions in
  `ExportProgress` **in one service-DB transaction with SKIPPED**; preserve last_slice/hash.
  A false revision therefore does not cause O(N) on every tick.
- **Service-DB `ExportProgress`, key `(profile, artifact)`:** `last_revision`,
  `last_sha256`, `last_slice_id`, `plan_hash`, `updated_at`. At `AVAILABLE→COMPLETED`,
  recover progress from manifest, update atomically with terminal CAS. Future append
  watermark gets a separate field/table activated only with append.

**V1 precision:** insert-only keep-first means “change” = `inserted>0` in **artifact tables**.
**`_sources`-only updates** (re-observation: last_seen/occurrences) do not change v1 export
columns (meta=NULL until OUT-1), so **do not bump revision**; content hash is the backstop.
When OUT-1 connects `time_last_seen` to `_sources`, the same write transaction must bump
revision for affected artifacts: explicit OUT-1 acceptance, not an optional revisit.

<a id="slice-retention-и-delivery-guard-f2"></a>
### Slice retention and delivery guard (F2)

Retention treats completed final directories as units, never ranks/deletes leaf files
individually. `SliceRetentionStore` lists only valid directories with `_SUCCESS`;
only export recovery cleans staging.

To implement 0012 before 0011 without a direct sync dependency, application port
`SliceRetentionGuard.canDelete(SliceDescriptor)` has two compositions:

- **Standalone export (no 0011 / sync disabled / no profile target):** guard permits
  max-age/max-count deletion;
- **Sync enabled:** 0011 `PublishLedgerSliceRetentionGuard` takes the current static
  configured target set for the profile. A missing `(slice_id,target_id)` pair is
  **undelivered**, blocking deletion and discovery/reaper races. Delete only when all
  expected pairs are `SUCCEEDED` or explicitly `ABANDONED`; `PENDING/IN_PROGRESS/FAILED`
  pin the slice. Permanent failure needs operator abandon or opt-in force-after,
  making max-count best-effort for pinned slices.

At startup, publish first reconciles `completed slices × configured targets` into
`publish_ledger`, then becomes running; maintenance has a later lifecycle phase.
Missing pairs still pin new slices until reconciliation. Process configuration is
immutable during lifecycle: target additions/removals apply after restart/reconcile.
`IN_PROGRESS` doubles as a lease: publisher CASes before opening files; reaper checks
the guard immediately before recursive deletion.

<a id="export-profiles-и-конфигурация"></a>
### Export profiles and configuration

A profile is the indivisible formation/publication unit. Minimal v1 form:

```yaml
ioc:
  export:
    enabled: true
    root: ./var/export
    trigger: { type: interval, interval: 5m }
    profiles:
      - name: reputation-lists
        output-mode: complete        # append валидатор отклоняет в v1
        artifacts: [ masks, ip_list, hashes ]
      - name: address-blacklist
        output-mode: complete
        artifacts: [ address_blacklist ]
    retention: { max-age: 7d, max-count: 3 }
```

Cross-validate profile/artifact names against `ioc.sink.artifacts`; profile fixes artifact
order/output-mode and includes them in plan hash. One run forms one profile; v1 global
single-flight serializes profiles to avoid multiple long SQLite readers. A 0011 target
contains `export-profile`, not `artifacts`, and copies the entire directory.

**CLI/service DB (ref-8).** Service datasource, migrations and export beans are no longer
daemon-only; they are `@Lazy`, conditional on `ioc.storage.service.type=jdbc`. Daemon
instantiates them through ingest/export dependencies; `ExportCommand` first calls an
IO-free profile validator, then resolves lazy `ExportArtifactsUseCase` only in `call()`.
Application service recovers before formation under the shared OS lease; `extract`,
`health`, `--help` and unknown profiles neither resolve storage graph nor open
`var/db/ioc-service.db`. CLI/daemon share DB-backed single-flight and export root.

<a id="решённые-развилки"></a>
### Resolved choices
- **A: separate `ExportRunLedger` port**, not generic `RunLedger<R>`. KISS; two sagas,
  two tables (`ingest_run` + new `export_run`), symmetrically. Generic only with a third saga.
- **B: framework-free `CadenceSource`** (`interval`/`quiet-period`+max-cap); Spring
  `SmartLifecycle` wiring in **bootstrap**, following `DaemonMaintenanceScheduler`
  and 0011 schedulers, not a separate `adapter-scheduler`. General cadence fulfills
  Q2 (reused by ING-7 and 0011 delivery).
- **C: `ArtifactSliceWriter` extends `adapter-sink-csv`** (reuse CSVPrinter + atomic move);
  JSON lives in `adapter-manifest-json-jackson` behind `SliceManifestCodec`. A new data
  format adapter only if format diverges from CSV (e.g. Parquet via DuckDB EXP-4 →
  `adapter-store-duckdb`).

<a id="наблюдаемость-и-проверочный-контракт"></a>
### Observability and verification contract

New `DiagnosticCategory.EXPORT`: at least `UNSUPPORTED_MODE`, `SNAPSHOT_READ_FAILED`,
`SLICE_WRITE_FAILED`, `MANIFEST_INVALID`, `ATOMIC_PUBLISH_UNSUPPORTED`,
`STATE_TRANSITION_CONFLICT`, `RECOVERY_FAILED`. ECS actions: `export_start`/
`export_complete`, `export_slice_write`, `export_recover`; fields `ioc.export.profile`,
`ioc.export.slice.id`, `ioc.export.revision`. Health exposes last completed/failed run,
slice age and per-profile revision lag. Add codes/fields with producers and register
in catalogs/whitelist under existing conventions.

Required tests before implementation readiness:

- unit: every CAS transition and crash-matrix row, including rename-before-ledger;
- JDBC: revision atomic with insert/rollback; concurrent commit after snapshot start
  absent from slice, visible to next pre-gate;
- contract: callback reader shares one snapshot across artifacts, closes resources on consumer exception;
- golden: deterministic CSV/manifest/root hash, UTF-8 JSON escaping, schema/plan-hash drift;
- integration: identical candidate → `SKIPPED` with revision advance; recovery never reads DB;
- retention: whole-directory deletion, missing/pending publish pair blocks, standalone permits;
  target always receives complete byte-identical profile slice;
- bootstrap: lazy service DB for export, none for extract/health; CLI+daemon single-flight;
  upgrade v4→v5 and fresh DB.

<a id="открытые-вопросы"></a>
## Open questions

1. ✅ **Resolved: v1 = `complete`; append deferred (F4).** Only complete in v1 (typical
   firewall/proxy/IDS consumer reloads a full list; correct for deletion/mutability).
   Append remains a designed extension, not built, rejected fail-fast
   (`EXPORT.UNSUPPORTED_MODE`). Rationale in §3a.
2. ✅ **Resolved: outside this iteration → ING-7.** **Local projection** performance
   (`*_generated.csv`), not export contract: export reads **DB truth** (`SELECT`), not
   local CSV, in a separate saga (`export_run`). Local cadence therefore does not
   affect delivery; future incremental projection is transparent. Deferral **does not
   complicate ING-7**: §4a trigger strategy/coalescing is the same pattern and is reused.
   **Guardrail:** keep cadence/trigger generalized (“cadenced emitter”, not export-only)
   so ING-7 can reuse it without redesign.
3. ✅ **Removed as Q1 consequence.** With accumulating complete, no late data: every
   snapshot is as-of current DB. Relevant only for opt-in append, which deliberately
   discards updates to already-shipped rows; consumers accept this when choosing it.
4. ✅ **Resolved: configured trigger, interval default.** Quiet-period + max-cap opt-in
   for bursts; on-new-rows deferred. Mechanism/rationale in Decisions §4a.
5. ✅ **Resolved: local slice-level retention; destination belongs to delivery (F2).**
   Completed `_SUCCESS` directories in `var/export/...` are deleted **whole**;
   max-age/max-count rank slices, not leaf files (`FileSystemRetentionStore` cannot be
   unit-store; need slice-store). Exclude staging/incomplete work (saga cleanup);
   delivery guard treats missing pairs as pending, retaining until 0011 completes/
   abandons all profile targets. Reuse ING-1 policy/scheduler, slice-level store/guard.
   Self-contained complete slices need little retention. **Destination retention**:
   delivery ([0011](0011-remote-sync.md) decision 18).

<a id="план-реализации-по-commit-срезам"></a>
## Implementation plan by commit slice

**Implementation status:** C0–C3 closed; durable foundation (application contracts,
canonical revisions/actual write count, CAS export state, global single-flight) passed
full `verify`. C4–C11 remain planned for subsequent waves.

Optimal granularity: **11 implementation commits plus separate design C0**. Fewer would
mix schema/storage, filesystem protocol and orchestration into changes too large to
review; far more would split transaction invariants. Each commit keeps the reactor green,
includes Javadoc, and updates each significant affected directory's README.

Slice dependencies:

```text
C0 docs
 ↓
C1 contracts → C2 revision → C3 durable state
 ↓
C4 manifest → C5 snapshot reader → C6 slice filesystem
 ↓
C7 saga → C8 CLI/manual export
 ↓
C9 daemon cadence ─┐
C10 retention ─────┴→ C11 final gate
```

<a id="c0--зафиксировать-ратифицированный-дизайн"></a>
### C0 — record the ratified design

**Commit:** `DOCS: finalize streaming artifact emission design`

Includes current 0012 changes and agreed 0011 amendments. Implementation baseline:
subsequent commits do not reopen accepted decisions without a discovered contradiction.
The local `Findings` working file is excluded from the commit.

**Gate:** `git diff --check`; clean tracked worktree after commit.

<a id="c1--application-модель-и-порты"></a>
### C1 — application model and ports

**Commit:** `ARCH: introduce artifact export contracts and model`

Add to `core/ioc-application`:

- `application/export`, `port/in/export`, `port/out/export` packages;
- `ExportProfile`, `ExportPlan`, `ExportMode`, `ExportRun`, `ExportRunStatus`;
- `SliceManifest` and per-artifact metadata/coverage;
- `SnapshotRequest`, `SnapshotMetadata`, `SnapshotRowConsumer`;
- in-ports `ExportArtifactsUseCase`, `RecoverExportUseCase`;
- out-ports `SnapshotSliceReader`, `ArtifactSliceWriter`, `ExportRunLedger`,
  `ExportProgressStore`, `ArtifactRevisionReader`, `SliceManifestCodec`;
- configuration-neutral retention model (`SliceDescriptor`, `SliceRetentionGuard`),
  a seam implemented in C10.

**Boundary:** framework-free contracts/invariants only. No JDBC, Spring, CSV,
filesystem orchestration or absolute paths. Append stays a model value for fail-fast/
future extension, but v1 use case does not execute it.

**Tests:** model validation/state invariants; deterministic plan hash; artifact order;
impossible status/coverage combinations.

**Focused gate:**

```bash
./mvnw -pl core/ioc-application -am test
```

<a id="c2--canonical-revision-и-фактический-write-count"></a>
### C2 — canonical revision and actual write count

**Commit:** `STORAGE: track canonical revisions and actual writes`

Core/JDBC write-path changes:

- dataframe migration `v3__artifact_revision.sql`, registered in `DataframeFormatMigrations`;
- `artifact_revision(artifact, revision, changed_at)`;
- `CanonicalArtifactRepository.write()` returns `CanonicalWriteResult(inserted, revision)`;
- `JdbcCanonicalArtifactRepository` sums `executeUpdate()` across public artifact rows;
- bump revision in the same JDBC transaction when `inserted > 0`;
- `JdbcIocSink.write()` returns actual inserted rows;
- JDBC `ArtifactRevisionReader`;
- adapt legacy importer, test fakes and all existing callers to the new contract.

**Keep separate** from export saga: this changes the existing oneshot/daemon write path
and needs independent review.

**Acceptance/tests:** duplicate preserves revision; atomic insert/revision; rollback
reverts both; fresh DB and upgrade v2→v3; existing ingest/projection/golden tests green.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-store-jdbc,bootstrap/ioc-app -am test
```

<a id="c3--durable-export-state-cas-и-single-flight"></a>
### C3 — durable export state, CAS and single-flight

**Commit:** `STORAGE: add CAS-backed export state and single-flight`

Implement:

- service migration `v5__export_state.sql`;
- `export_run`, `export_progress`, indexes, partial active-run unique index;
- `JdbcExportRunLedger`, `JdbcExportProgressStore`;
- CAS every transition, reread row when `affected=0`;
- atomic service-DB `progress + COMPLETED/SKIPPED`;
- retrofit CAS into existing `JdbcRunLedger`;
- reusable export-ledger contract tests/TCK.

**Acceptance/tests:** allowed/forbidden transitions; idempotent replay; concurrent start;
crash retains active run for recovery; skipped advances revision; fresh DB, upgrade
v4→v5; incompatible terminal status emits diagnostic rather than silent no-op.

**Wave gate after C3:**

```bash
./mvnw verify
```

<a id="c4--deterministic-json-manifest-codec"></a>
### C4 — deterministic JSON manifest codec

**Commit:** `ADAPTER: add deterministic slice manifest JSON codec`

Create `adapters/adapter-manifest-json-jackson`:

- Jackson is the only integration-library family;
- `SliceManifestCodec` implementation;
- deterministic UTF-8 serialization with fixed field order;
- round-trip parsing for future 0011 consumer;
- manifest hash over actual written bytes;
- parent reactor/dependencyManagement/bootstrap dependency;
- module/directory README and ArchUnit boundary.

**Acceptance/tests:** Unicode/escaping/nulls; stable golden bytes; round-trip; reject
unknown incompatible manifest_version; runtime-map reordering preserves output.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-manifest-json-jackson -am test
```

<a id="c5--строгий-callback-streaming-snapshot"></a>
### C5 — strict callback-streaming snapshot

**Commit:** `STORAGE: stream consistent multi-artifact snapshots`

Implement `JdbcSnapshotSliceReader`:

- one connection, one explicit read transaction per profile;
- coverage for all profile artifacts from the same snapshot;
- synchronous `begin/beginArtifact/row/endArtifact` callbacks;
- sequential `ORDER BY id` across all artifacts;
- JDBC owns connection/transaction/cursor entirely;
- consumer gets no JDBC types and retains no rows;
- schema/identity/revision metadata returned with snapshot.

**Acceptance/tests:** multiple tables share one as-of point; later commit excluded now,
visible next time; consumer exception closes resources; no `CanonicalArtifact`/full
row collection; fail-fast unknown artifact.

**Focused gate:**

```bash
./mvnw -pl adapters/adapter-store-jdbc -am test
```

<a id="c6--csv-staging-и-атомарная-локальная-публикация"></a>
### C6 — CSV staging and atomic local publication

**Commit:** `SINK: materialize and atomically publish immutable CSV slices`

Implement in `adapter-sink-csv`:

- `CsvArtifactSliceWriter` as synchronous `SnapshotRowConsumer`;
- `CSVPrinter → DigestOutputStream`, single-pass SHA-256 and row count;
- staging layout/data files, codec manifest, `_SUCCESS` containing manifest hash;
- file `force(true)` and directory fsync;
- `stage`, `verify`, `makeAvailable`, recovery filesystem inspection;
- atomic staging→final rename, no copy fallback;
- `SLICE_WRITE_FAILED`, `MANIFEST_INVALID`, `ATOMIC_PUBLISH_UNSUPPORTED` with producers.

**Boundary:** writer knows neither ledger/status transitions nor service DB reads;
it owns only deterministic bytes and filesystem protocol.

**Acceptance/tests:** deterministic tree/CSV/manifest; marker last; final hidden before
rename; corruption detected; repeat verification idempotent; unsupported atomic move
fails fast; heap does not grow with row count.

**Wave gate after C6:**

```bash
./mvnw -pl adapters/adapter-manifest-json-jackson,adapters/adapter-store-jdbc,adapters/adapter-sink-csv -am test
./mvnw verify
```

<a id="c7--export-saga-change-detection-и-recovery"></a>
### C7 — export saga, change detection and recovery

**Commit:** `FEATURE: orchestrate export saga and forward recovery`

Add to `core/ioc-application`:

- `ExportService`, `ExportRunRecoveryService`, `ExportChangeDetector`;
- revision/plan-hash pre-gate;
- materialization, authoritative post-hash;
- `STARTED → STAGED → AVAILABLE → COMPLETED`, `SKIPPED`, `FAILED`;
- DB-backed single-flight;
- full crash matrix;
- forward recovery from staging/final+manifest only, no repeated snapshot;
- `STATE_TRANSITION_CONFLICT`, `RECOVERY_FAILED`, export ECS events with actual producers.

**Acceptance/tests:** every crash-matrix row; manifest without marker; marker before
ledger update; rename before available; available before progress; corruption;
duplicate→skipped with revision advance; recovery fake proves no SnapshotSliceReader call.

**Focused gate:**

```bash
./mvnw -pl core/ioc-application -am test
```

<a id="c8--config-lazy-wiring-и-ручной-cli-export"></a>
### C8 — config, lazy wiring and manual CLI export

**Commit:** `FEATURE: expose on-demand artifact export`

Add:

- `IocProperties.Export`, profiles/trigger/retention records/defaults;
- profile↔enabled sink-artifact cross-validation, name uniqueness/order;
- fail-fast `EXPORT.UNSUPPORTED_MODE` for append;
- safe default in `application.yml`;
- lazy service datasource/migrations/export graph, not daemon-only;
- new adapter/port composition in `AppConfig`;
- `ExportCommand`, root CLI registration, `ioc export --profile ...`;
- recovery before manual execution;
- CLI/config/application-context integration tests.

**Acceptance:** manual export yields valid final slice; unchanged repeat skipped;
unknown profile/artifact and append fail before IO; extract/health/help open no service
DB; CLI/daemon conflict correctly through single-flight.

**Milestone:** first complete user path after C8: on-demand complete export, without scheduler/retention.

**Wave gate after C8:**

```bash
./mvnw -pl adapters/adapter-cli-picocli,bootstrap/ioc-app -am test
./mvnw verify
```

<a id="c9--cadence-и-daemon-scheduler"></a>
### C9 — cadence and daemon scheduler

**Commit:** `FEATURE: schedule exports by canonical cadence`

Implement:

- framework-free `CadenceSource`/policy, injected `Clock`;
- interval, quiet-period, mandatory max-cap;
- bootstrap `DaemonExportScheduler`;
- sequential profile traversal under global single-flight;
- recovery fully complete before scheduler start;
- revision/changed_at as quiet activity source;
- overlap guard, controlled shutdown;
- export health: last completed/failed, slice age, per-profile revision lag.

**Acceptance/tests:** interval/debounce; duplicate input does not reset quiet-period;
max-cap prevents starvation; restart; slow runs do not overlap; tick failure does not
kill daemon and retries next tick.

<a id="c10--slice-level-retention-и-standalone-guard"></a>
### C10 — slice-level retention and standalone guard

**Commit:** `MAINT: reap completed export slices safely`

Implement:

- `SliceRetentionStore` in local slice/filesystem adapter;
- `SliceRetentionGuard` + standalone implementation;
- reuse pure `RetentionPolicy`, not leaf-file `FileSystemRetentionStore`;
- directory-as-unit deletion, per-profile max-count;
- no automatic deletion of staging/incomplete/corrupt final;
- seam for future 0011 `PublishLedgerSliceRetentionGuard`;
- maintenance lifecycle integration via named second scheduler or composite maintenance
  use case, keeping leaf and slice stores separate.

**Acceptance/tests:** whole-directory delete; independent profile pools; guard blocks;
standalone permits; pinned slices make max-count best-effort; reaper leaves staging
and never hides corruption.

<a id="c11--полный-gate-observability-и-опубликованные-docs"></a>
### C11 — full gate, observability and published docs

**Commit:** `TEST: gate artifact emission end to end`

Final hardening:

- E2E `canonical write → revision → snapshot → CSV → manifest → _SUCCESS`;
- concurrent ingest during export;
- duplicate/skipped;
- all crash windows with fault injection;
- CLI export, daemon cadence, restart, retention;
- fresh/upgrade migrations;
- ArchUnit: no inward Jackson/JDBC/Spring leakage;
- golden export slice;
- complete EXPORT catalog, ECS whitelist, health assertions.

Update published `architecture.md`, `modularization.md`, `ingestion.md`,
`diagnostics.md`/generated catalog, `techdebt.md`, 0012 roadmap/status and directory README.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

**C11 implementation:** E2E combines canonical write, revision, real JDBC snapshot,
CSV/manifest/marker, duplicate skip and concurrent commit followed by catch-up run.
Other checks follow ownership: recovery crash windows in application; atomic publication/
corruption in CSV adapter; snapshot isolation/fresh+upgrade migrations in JDBC;
cadence/health/retention/ECS in bootstrap/platform. Golden CSV is a separate test
resource; generated catalog and ArchUnit rules are in shared verify.

<a id="группировка-для-реализации"></a>
## Implementation grouping

Recommend **four separate waves**, with fresh context at each boundary:

| Wave | Commits | Responsibility owner | Result / handoff |
|---|---|---|---|
| A — Durable foundation | C1–C3 | application contracts + JDBC state | Stable ports, revision, CAS ledger/progress; full verify |
| B — Slice formation | C4–C6 | JSON codec + JDBC snapshot + CSV/filesystem | Deterministic staged/available slice, without application orchestration |
| C — Vertical MVP | C7–C8 | application saga + bootstrap/CLI | Working manual ioc export with recovery/skip |
| D — Operations | C9–C11 | cadence + retention + health/gates | Complete daemon-ready flow and published docs |

Handoff must contain:

- completed commit hash range and clean git status;
- fixed public interfaces/migrations;
- modules allowed in the next wave;
- focused-test and latest `./mvnw verify` results;
- known next-wave seams, without proposing changes to closed decisions;
- explicit preservation of others'/user changes and exclusion of unrelated files.

Within each wave:

1. One commit per listed invariant; no squashing distinct slices before review.
2. Focused module tests first, full verify at wave end.
3. Add diagnostic code/event/field with its producer in the same commit.
4. Create migration once; after commit, corrections use a subsequent version.
5. New significant directory gets README immediately; complex public/module classes get Javadoc.
6. Bootstrap owns only composition/lifecycle; SQL in JDBC, CSV/filesystem in sink, saga/policy in application.
7. Before handoff, compare the assigned acceptance criteria line by line.

Safe parallelism requires separate worktrees/branches:

- After C3, C4 codec and C5 snapshot are nearly independent, but merge both before C6.
- After C8, C9 cadence and C10 retention are conceptually independent, but both touch
  AppConfig/IocProperties; execute sequentially in one worktree.
- Do not parallelize C2/C3 or C6/C7: shared migrations/contracts and filesystem/saga protocol.
