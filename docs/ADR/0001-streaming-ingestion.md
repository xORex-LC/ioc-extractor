> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0001-streaming-ingestion.md) (relative link addresses rebased on relocation).

<a id="0001--стриминговый-инжест-демон"></a>
# 0001 — Streaming ingestion (daemon)

- **Status:** accepted; updated after stage 9; **partition/aggregation portion
  superseded by the storage collapse** (see below)
- **Date:** 2026-06-21
- **Related:** [../ingestion.md](../dev/ingestion.md), [../architecture.md](../ARCHITECTURE.md),
  [observability](../dev/observability.md), [modularization](../MODULARIZATION.md)

> **Superseded (storage Step 1–3 / β-collapse):** this ADR is the historical record
> of the original design with partition staging, a separate aggregation pass and
> a stable-ID sidecar. These were **removed**: business data now lives in canonical
> SQLite (truth), the daemon writes each file directly to canonical storage + CSV
> projection, and one `IdGenerator` assigns IDs. The current flow is described in
> [../ingestion.md](../dev/ingestion.md) and [../ARCHITECTURE.md](../ARCHITECTURE.md).
> What follows describes the earlier design.

> **Amendments after review ([0006](0006-design-review-refinements.md)) and
> modularization:** `SourceFeed` is an adapter-local abstraction over Spring
> Integration, not a core port; the driving port is `IngestSourceUseCase`.
> Stage 10 implements whole-file daemon ingestion + partition output. Tail,
> Aggregator, retention and the SQLite ledger remain subsequent steps/extensions.

<a id="контекст"></a>
## Context

The CLI “one run → exit” invocation remains supported, but is **not the primary**
mode. The primary mode is a long-running service (container or systemd), always
`active`, scanning a source directory and processing new files/records as they
arrive. Requirement: **no data loss, no duplicate processing, survive crashes**.
Detection, idempotency, output writing and lifecycle need to be designed.

Architecturally, the daemon is a **driving adapter** over the existing
`ExtractIocsUseCase`; the domain core does not change.

<a id="решения"></a>
## Decisions

| # | Choice point | Choice | Rationale | Rejected |
|---|---|---|---|---|
| 1 | Nature of “new records” | **Whole-file now, tail as a later seam** through adapter-local `SourceFeed` | Stage 10 must cover the primary discrete-document flow without tail-checkpoint complexity; growing feeds need a separate offset/rotation model | Tail only (would miss discrete drops); tail immediately (inflates stage 10) |
| 2 | Detection mechanism | **Hybrid:** WatchService + periodic reconciliation | Low inotify latency + polling safety net (events are lost on overflow and network/mounted filesystems) | Pure inotify (unreliable on mounts/NFS); pure polling (higher latency) |
| 3 | Ingestion technology | **Spring Integration (file)** | Production-ready filters (persistent accept-once, stability), atomic rename, poller, tail producer, error channel, retry — less reinvention | Custom watcher behind a port (more code for the same guarantees) |
| 4 | Streaming output writing | **Partitions in stage 10, aggregation in stage 11** | A per-source partition is idempotent and race-free; a separate aggregator later assembles the canonical artifact and resolves global IDs | Appending to one artifact (atomicity/locking/durable IDs are fragile under concurrency) |
| 5 | Partition location | **Separate subdirectory** `dataframe/partitions/` (configurable, gitignored) | Keep intermediate partitions apart from canonical artifacts in `dataframe/` | Partitions beside artifacts (interference, confusing lookup) |
| 6 | Partition cleanup (retention) | **Separate optional `PartitionReaper` behind `RetentionPolicy`**, off by default; reserve the seam now | Move cleanup aside without coupling it to the main flow; add later without changing that flow | Deletion directly in the aggregator (couples responsibilities, risks loss on failure) |
| 7 | Concurrency | **Multithread-ready design, initially single-threaded implementation** | KISS: no concurrency before it is needed, but establish invariants (atomic claim, idempotency, IDs at aggregation, single-writer aggregator) → add parallelism later without redesign | Multithreading immediately (premature complexity/risk); single-threaded without foresight (risks rework) |

<a id="следствия"></a>
## Consequences

- **New inbound adapter** `adapters/adapter-ingest` (Spring Integration); SI stays
  inside this adapter. `SourceFeed` remains adapter-local.
- **New application ports:** `IngestSourceUseCase`, `IngestionLedger`,
  `SourceLifecycle`, `PartitionSinkFactory`. `SourceLifecycle` hides filesystem
  claim/archive/fail operations behind a driven port; `PartitionSinkFactory`
  hides the daemon partition-path strategy behind an adapter implementation.
- **Stage 10 output:** the daemon writes partitions (atomically, keyed by
  content-hash/source-key) through a separate partition wrapper in the adapter
  layer. Do not extend the core `IocSink` API in stage 10.
- **Stage 11 output:** a separate `Aggregator` combines partitions into a canonical
  artifact and assigns global IDs. It resolves the stable `id auto` TODO.
- **Directory state machine** `inbox → processing → done | failed`, with atomic
  moves to prevent duplicate processing and data loss on crashes.
- **Two-layer idempotency:** SI accept-once (name+mtime) + our content-hash ledger
  (detects renames/duplicates).
- **Lifecycle:** graceful shutdown on SIGTERM; two run modes through
  `ioc.runtime.mode` (`oneshot` | `daemon`), with a Spring profile for
  runtime/config overrides. `System.exit` is allowed only for `oneshot`.
- **New libraries:** `spring-boot-starter-integration` + `spring-integration-file`,
  `spring-retry`; Actuator moves to after stage 11, SQLite/tail libraries remain
  later/optional. JDK `MessageDigest` computes SHA-256 without `commons-codec`.
  Details: [../ingestion.md](../dev/ingestion.md).
- Connects to the cross-cutting **error subsystem** (fail-fast versus
  collect-and-continue policy, dead letter) — [observability](../dev/observability.md).

<a id="открытые-вопросы"></a>
## Open questions

- **Health transport:** select Actuator/heartbeat after stage 11, once daemon +
  aggregator form a complete runtime flow. Does not block stage 10.
- **Ledger/metadata store:** file-backed (`PropertiesPersistingMetadataStore`)
  initially, SQLite (`spring-integration-jdbc`) when durability requirements grow.
- **Stage 11 aggregation policy:** scheduled versus “partition ready” event;
  global-ID strategy (continue from the maximum in the canonical artifact).
- **Partition wrapper contract:** keep the wrapper in the adapter layer without
  introducing source-key into domain/application APIs beyond existing envelope metadata.
- **Retention:** criterion (age/size) and default mode (delete/archive/compress)
  when `PartitionReaper` is enabled.
