> English translation of the historical Russian record. Decision/status/dates are preserved. [Russian original](ru/0013-event-driven-coordination.md) (relative link addresses rebased on relocation).

<a id="0013--гибридная-модель-координации-control-plane-события--edge-io--etl-ядро"></a>
# 0013 — Hybrid coordination: control-plane events + edge IO + ETL core

<a id="статус"></a>
## Status

**Accepted, not implemented.** Design recorded after a discussion informed by operating
[0011](0011-remote-sync.md) (delivery/SMB) and
[0012](0012-streaming-dataframe-emission.md) (export slices). Introduces a **three-plane
coordination model** and event infrastructure (new `platform-events` module).
It **implements and replaces** 0011's “Post-v1 clarification: detection and execution must
be separated” and technical debt **OPS-4**, generalizing sync hardening into a reusable
application foundation. No code yet; rationale, model, applicability, rejected alternatives
and implementation slices follow.

**Post-review hardening incorporated (R0–R6):** **R0**:
`@TransactionalEventListener(AFTER_COMMIT)` cannot work without an enclosing use-case
transaction (transactions stay inside JDBC adapters); publish-after-commit by **program
order** + reconcile backstop (decision 8). **R1**: base type `ControlEvent`, not
`DomainEvent` (DDD terminology, decision 2). **R2**: **publish-only** port, no
`EventSubscriber` SPI (consume seam = command, clarified R29), decisions 2/2a.
**R3**: **slice-specific** publish fast path (`PublishCompletedSliceCommand` +
`CompletedSliceCatalog.find`, decision 5). **R4**: fetch executor is a **command handler**,
detection belongs in `RemoteSourceMonitor` (decision 5). **R5**: event **metadata envelope**
(decision 6). **R6**: **`KeyedSerialExecutor`**, rather than general `@Async` pool (decision 9).

**Second review (R7–R13):** **R7**: `publish` is **fire-and-observe**; dispatch failure does
not fail producer, errors go to diagnostics/observer, correctness to reconcile (decision 2).
**R8**: `eventId` is not business idempotency key (Idempotent Receiver by
`(sliceId,targetId)` / `RemoteObjectIdentity`, decision 6). **R9**: include `eventVersion`
from the outset (decision 6). **R10**: executor **FIFO/coalescing retains the fact of work**,
no dropping; bounded queue and shutdown/rejection policy backed by reconcile (decision 9).
**R11**: monitor fingerprint cache improves latency, not source of truth; ledger protects
restart re-emission (decision 5). **R12**: one `SpringControlEventPublisher` bridge; no direct
`ApplicationEventPublisher` calls (decisions 2/6). **R13**: emit `SliceCompleted` only in
`AVAILABLE→COMPLETED`, not general `ExportObserver.completed`, also called for `SKIPPED`
(decision 5).

**Third review (R14–R23):** **R14**: rename `EventBus` to **`ControlEventPublisher`**
(publish-only, in-process, non-durable; README disclaimer), bridge
`SpringControlEventPublisher` (decision 2). **R15**: **event=fact / command=intent**;
ban `*Requested` pseudo-events (decision 6). **R16**: **MDC/correlation propagation** through
keyed executor (`MdcScope`, decision 9). **R17**: **queue health** (depth/oldest-age/rejected/
running keys, decision 10). **R18**: executor key by **external resource**
(`endpoint+remotePath` for overlap), not just ID (decision 9). **R19**: **bounded batch**
`RemoteChangeBatchDetected(…, hasMore)`, no large payload (decisions 5–6). **R20**:
mandatory **startup reconcile before fast path** (decision 7). **R21**: **single-instance
v1**; multi-instance requires leases/fencing seam (decision 13). **R22**: **consumer
failure/poison**: bounded retry → ledger `FAILED` → release key → reconcile
(**operator-visible failed state, no custom DLQ**, decision 9). **R23**: **TCK-style
contract tests** for `ControlEventPublisher`/`KeyedSerialExecutor` (E0/E1).
**R24**: architectural boundary: `platform-events` = event model + publishing contract;
delivery = adapters; **no broker inside the core** (decisions 2/11).

**Fourth pass — reference-grounded review + anti-broker invariant (R25–R35):**
**R25**: fundamental **anti-broker** invariant: `platform-events` = model + publish
contract, **not** delivery mechanism; “core provides / does not implement / delegates”
checklist and “broker arrival = zero core changes” diff (decision 2a).
**R26**: move `KeyedSerialExecutor` **out of** events (general concurrency primitive,
cohesion/SRP), decision 9. **R27**: split decision 9 into (1) permanent per-key
single-flight, (2) replaceable admission/queue seam, (3) redelivery/durability/DLQ
(**not built**: reconcile now, broker later). **R28**: executor key = **endpoint**, the
actual transport serialization unit; per-path parallelism within an endpoint is not
available in single-writer v1. **R29**: consume seam = **command** (use-case input port),
not subscriber SPI; broker listener translates to the same command.
**R30**: reconcile can be **DB-driven** (SQLite ledger arrived with ING-4), avoiding
filesystem rehash. **R31**: explicit **retention invariant**: retain a slice with any
non-`SUCCEEDED` pair (`SliceCompleted`→`find` TOCTOU). **R32**: **admission control** on
saturated keys, avoiding bounded-queue↔reconcile livelock. **R33**: **Spring Modulith Event
Publication Registry** is a delivery adapter behind the port, rejected on **granularity**:
only ledger reconcile retries partial target failures and lost triggers; Modulith is
**additive, not replacing** (Rejected alternatives). **R34**: references: decision 8 ←
Spring docs, 6 ← Fowler (Event Notification), 5 ← EIP, 9 ← Akka/single-writer.
**R35**: unify `RemoteChangeDetected` as `RemoteChangeBatchDetected`, clarify E2 ordering
(C2) and cross-run `correlationId` (C3).

**Fifth pass — open-question resolutions + verification (R36):** Q4–Q9 closed.
**Q9**: monitor uses `PeriodicDaemonCycle` (no CHANGE_NOTIFY/SI; short `fetch.interval`
provides latency). **Q6**: DB-driven `publish_ledger` reconcile O(pending) + discovery
directory listing/anti-join/rehash-once (F3 retained). **Q7**: per-key high/low-water
admission hysteresis, no-op shedding in reconcile, code-owned rule. **Q4**: fingerprint
is `(path,size,mtime)` set + set-diff, same fetch-ledger key, no aggregate hash.
**Q5**: do not lift transaction boundary (saga + reconcile backstop; adapter-local
outbox); **commit↔event gap verified covered in code** (atomic rename makes `_SUCCESS`
durable before COMPLETED+event; discovery reads filesystem, not events).
**R36**, found in verification: `listCompleted` throws on the first corrupt child
directory; E5 requires **resilient discovery** (skip+quarantine+diagnose, not abort).

Grounded in current code: `platform-etl` (Pipes-and-Filters:
`Stage`/`Envelope`/`PipelineRunner`/`PipelineObserver`), `application/sync`
(`Retrier`/`RetryPolicy`, fetch/publish use cases), JDBC ledgers (`RemoteFetchLedger`,
`PublishLedger`), `adapter-ingest` (only production Spring Integration),
`DiagnosticSink`/ECS observability, `DaemonFetchScheduler`/`DaemonPublishScheduler`.

<a id="контекст"></a>
## Context

<a id="триггер--реальный-прогон-0011-на-хосте"></a>
### Trigger — a real 0011 host run

Sync delivers correctly but exposes a structural defect: **detection is not separated
from execution**. Each responsibility working with remote storage initiates an operation
on every tick of its own timer.

Steady-state log (both schedulers tick about every 10 seconds with empty storage):

- **Fetch** ([`DaemonFetchScheduler`](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DaemonFetchScheduler.java)
  → [`RemoteFetchService.fetch`](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/RemoteFetchService.java))
  starts every tick with `transport.list(endpoint, remotePath)`, a **remote SMB call**
  serving both “did something change?” and execution. Empty source:
  `fetched=0, skipped=7` and two INFO lines.
- **Publish** ([`DaemonPublishScheduler`](../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/DaemonPublishScheduler.java)
  → [`ArtifactPublishService.publish`](../../core/ioc-application/src/main/java/com/iocextractor/application/sync/ArtifactPublishService.java))
  performs **local** `sliceCatalog.listCompleted(profile)` every tick: `var/export`
  scan + manifest decoding + **rehash of every slice file** + `ensurePending` for every
  `(slice, target)`, even if all are already `SUCCEEDED`. No SMB call, but expensive
  idempotent no-op IO and two INFO lines.

<a id="что-не-является-проблемой-сверено-с-кодом"></a>
### What is NOT a problem (checked against code)

- **Transport is thread-safe and well implemented.**
  [`SmbFileTransport`](../../adapters/adapter-transport-smb/src/main/java/com/iocextractor/adapter/out/transport/smb/SmbFileTransport.java)
  has per-endpoint `synchronized`, a `ConcurrentHashMap` client cache, transient-failure
  eviction and real idle-timeout `closeIdle()`. Steady-state connections are **warm and
  reused** (touched every ~10s, never reach idle 5m). The logged reconnect is a
  **one-off** stale session (server dropped it: `Read timed out`), not systemic thrashing.
- “Constant remote calls” therefore means only cheap fetch `list`; idle publish makes
  no remote call. The pain is **no-op detection embedded in execution** (network for
  fetch, local IO for publish) and **no-op log spam**.

<a id="индустриальная-опора"></a>
### Industry foundations

- **EIP** (Hohpe & Woolf, *Enterprise Integration Patterns*): Message/Channel/Endpoint,
  **Polling Consumer vs Event-Driven Consumer**, **Channel Adapter**, **Idempotent
  Receiver**, Dead Letter Channel. Pipeline: **Pipes-and-Filters**.
- **Spring `ApplicationEventPublisher`**: built-in in-process Observer/pub-sub;
  arbitrary POJO events since 4.2; `@TransactionalEventListener(AFTER_COMMIT)`,
  `@Async`, `@Order`, SpEL condition, configurable `ApplicationEventMulticaster` `ErrorHandler`.
  **No** retry/DLQ/serialization/durability: a delivery primitive, not a foundation.
- **Transactional Outbox** (Richardson, microservices.io): named durable at-least-once
  solution **if** in-process events are insufficient. Classic outbox solves **DB +
  broker** dual writes; no broker here, so YAGNI (decision 11). Registry/outbox **could**
  close state-change+dispatch crash atomicity, but this ADR uses **reconcile over
  ledgers** (decisions 7/8, gap verified Q5); Registry considered and **rejected** below.
- **Spring Modulith Event Publication Registry**: durable publication journal
  (`PUBLISHED→PROCESSING→COMPLETED/FAILED`, staleness monitoring, restart republish,
  JDBC starter), canonical in-process outbox. A **candidate delivery adapter** behind
  our port, not core; rejected in favor of ledger reconciliation (decisions 7/11).
- **Event Notification vs Event-Carried State Transfer** (Fowler, “The Many Meanings
  of EDA”): **thin** events (ID + envelope, not data); consumer calls producer to read
  state (`CompletedSliceCatalog.find`). Large ECST payload rejected (decision 6/R19).
- **Data-plane orchestrators** (Beam, NiFi, Airflow) move/transform **data**, rather
  than coordinate internal services. Confusing the planes is a category error,
  explicitly separated below.

<a id="сквозной-принцип-проекта-несущая-стена"></a>
### Cross-cutting project principle (load-bearing)

`core/ioc-domain` and `core/ioc-application` are **framework-free** (ArchUnit bans
Spring/SI; frameworks only in adapters + bootstrap). All event decisions must preserve this.

<a id="решения"></a>
## Decisions

<a id="1-три-плана-координации-каждый-в-родной-роли"></a>
### 1. Three coordination planes, each in its native role

Three orthogonal planes are the central ADR decision; the rest supplies details.

| Plane | Purpose | Tool | Location |
|---|---|---|---|
| **Control-plane** | Service coordination: **facts/signals**, not data | **`ControlEventPublisher` port** (current adapter: Spring `ApplicationEventPublisher`) | port in platform-events, adapter in bootstrap |
| **Transformation** | Business ETL for one source: refang/extract/classify/dedup/write | **`platform-etl`** (Pipes-and-Filters as **library**) | framework-free core |
| **Edge-IO** | Move **bytes across the system boundary** | **Spring Integration** (Channel Adapter/Polling Consumer) | adapters |

**Rationale.** Different control models: control-plane decouples events (IoC into our
code through a port), transformation composes functions synchronously (we own control
flow), edge IO delegates boundary polling to a framework. Mixing planes caused the
original defect (detection within execution) and temptation (SI-based ETL). Separation
provides one **mental** coordination framework while preserving framework-free core.

<a id="2-control-plane--applicationeventpublisher-за-publish-only-портом-controleventpublisher-новый-platform-events"></a>
### 2. Control-plane — ApplicationEventPublisher behind publish-only ControlEventPublisher (new platform-events)

> **Architectural boundary fundamental to this ADR:**
> **`platform-events` defines the application event model and publishing contract.
> Delivery mechanisms are adapters.**
>
> This is not our RabbitMQ/Kafka: a small **EIP vocabulary/contract core**, **not** a
> broker implementation. Spring events today, outbox/RabbitMQ/Kafka tomorrow are
> **pluggable delivery adapters behind the same port**, without core rewrites.
> This is the OCP seam: new delivery = new adapter, no core change. Adding queue/
> redelivery/DLQ/serialization/routing inside platform-events indicates drift into a
> homemade mini-RMQ and **must be rejected** (durable machinery in delivery adapters,
> decision 11).

Control events use a **framework-free port**; Spring events are an **adapter detail**.
Same repository pattern as `DiagnosticSink` → `LoggingDiagnosticSink` and
`CanonicalArtifactRepository` → Hikari adapter.

- New **`platform-events`** module, alongside platform-etl/diagnostics:
  - **`ControlEvent`** base, NOT DomainEvent: in DDD a domain event is a domain-model/
    aggregate fact; SliceCompleted/RemoteChangeBatchDetected/PublishCompleted are
    application/control-plane facts. Preserve terminology. Minimal **metadata envelope**
    (decision 6), IDs/counts, **no business data/bytes/rows**; bounded work references/
    identities allowed, e.g. `RemoteChangeBatchDetected.items` (decision 6/R19).
  - **Publish-only `ControlEventPublisher`**, more accurate than EventBus: **not** durable
    bus/broker/routing framework; README must state this. `publish(ControlEvent)`.
    V1 subscribers are bootstrap Spring `@EventListener` beans translating events to
    **commands** (use-case input ports). **Never introduce** framework-free
    `EventSubscriber` SPI, rather than merely deferring it: stable consume seam is
    the **command**; a broker listener later translates to **the same command**
    (decision 2a/R29). Subscriber SPI would be premature broker infrastructure.
  - Test doubles **`NoopControlEventPublisher`**, **`RecordingControlEventPublisher`**.
  - Bootstrap implementation **`SpringControlEventPublisher`**, the single bridge below.
  - `ControlEventObserver` publication observability bridges existing ECS, no new stack.
- **Fire-and-observe, producer-neutral `publish` contract.** Dispatch/admission errors
  **do not propagate** to producer; business outcome **does not depend** on dispatch.
  Failures go to **diagnostics + `ControlEventObserver`**, correctness recovers through
  **reconcile** (decision 7). Otherwise durable completed export could still fail CLI/
  daemon merely because its fast-path hint could not be queued.
- Concrete events live **beside their bounded area** in core/ioc-application, not in
  a platform-events dumping ground.
- **One bootstrap bridge bean** `SpringControlEventPublisher` is the sole publication
  point for framework-free core publishers and adapter-originated events (inbox SI
  flow / PeriodicDaemonCycle monitor). **No direct ApplicationEventPublisher calls**,
  which bypass observers and consistent observability. Core still sees only the port.
- Subscribers are `@EventListener` driving beans calling use-case commands.
  Transactional listeners/async only where technically correct (decisions 8–9).

**Why not SI MessageChannel as event bus?** SI is currently an inbound channel adapter
(decision 4), not a domain event bus. Control events through SI add another SI surface;
in-JVM producer/consumer decoupling is precisely ApplicationEventPublisher's role.
A 30-line Spring adapter is simpler. Use SI only for durable queues/multi-step routing
(decision 11).

<a id="2a-anti-broker-boundary--несущий-инвариант-платформы"></a>
### 2a. Anti-broker boundary — the platform's load-bearing invariant

> **platform-events is a contract core (event model + publish contract), NOT delivery.**
> It **does not implement** external-broker concerns: these live behind the port in
> adapters now or arrive with a broker later. **Broker arrival = new adapter behind
> the same port, unchanged core.** Check every platform-events PR against the table
> below to prevent a homemade mini-RMQ.

| | Concern | Location |
|---|---|---|
| ✅ **Core provides** | `ControlEvent` + immutable envelope (`eventId/eventType/eventVersion/occurredAt/correlationId/causationId?`), the **integration contract** (broker maps envelope to headers) | platform-events |
| | publish-only ControlEventPublisher, ControlEventObserver, test doubles | platform-events |
| | thin-fact Event Notification discipline | platform-events |
| ❌ **Core does NOT implement** (broker concerns) | durable queue · redelivery / ack-nack / delivery retry · Dead Letter Channel · serialization/wire format · routing/subscription matching · interprocess ordering/competing consumers | — (broker) |
| 🔌 **External now, replaced by broker later** | in-JVM dispatch | SpringControlEventPublisher (bootstrap) |
| | execution + per-key single-flight | KeyedSerialExecutor, outside events (decision 9) |
| | at-least-once | ledger reconcile, retained **with** broker |

**Consume seam = command, NOT subscription SPI.** Stable receiving boundary is a
**use-case input port**, not EventSubscriber. A thin EventListener translates to
PublishCompletedSliceCommand/RemoteFetchWork and invokes the handler; a future
BrokerListener translates to **the same command**. Only the translator changes;
handler/use case stay. Deferring subscriber SPI (decision 2) therefore **preserves**
the invariant rather than leaving incomplete work: otherwise premature broker SPI.

**Broker integration day diff (replaceability check):**
- New broker-backed implementation of the same ControlEventPublisher port.
- `@BrokerListener` beside `@EventListener` → **same commands**.
- Bounded in-memory executor queue replaced by broker queue (admission seam, decision 9).
- Ledger reconciliation **remains**, belt-and-suspenders over broker at-least-once.
- Per-key single-flight **remains**, or moves into broker key partitioning.
- **Zero platform-events changes**: envelope already maps to headers, port already publish-only.

ArchUnit guards: no queue/broker/serialization dependencies in events; executor
**outside** events; ControlEvent **not Serializable** before a broker (serialization
activated only by delivery adapter).

<a id="3-platform-etl-остаётся-ядром-трансформации-si-в-ядро-не-вносим"></a>
### 3. platform-etl remains the transformation core; no SI in core

Pipes-and-Filters is a pattern; SI is one engine; **we already implemented the pattern**
framework-free within our boundaries. Rebuilding ETL on SI has two bad outcomes:

- **(a)** POJO stages wired by SI service activators: Stage/Envelope stay wrapped,
  gain ≈0, orchestration-path dependency +1;
- **(b)** SI endpoint stages (`@Transformer`/`@Splitter`): domain logic (refang, RE2,
  PSL, match-policy) imports `org.springframework.integration.*`, losing portability and
  framework-free testability, failing ArchUnit.

The clean version (a) is useless; useful version (b) breaks the load-bearing boundary.

**Primary reason: Library vs Framework / IoC direction.** platform-etl is a **library/SPI**:
we own `runner.run(stages, envelope)`. SI is a **framework** owning orchestration.
Hexagonal architecture keeps core control flow out of framework ownership. Same rationale
as rejecting Camel in 0011 and Spark in 0012: Dataflow model without a heavy framework.

**Core needs none of SI's extras** (linear flow, one file at a time, single-thread daemon):
Retrier already exists; error channel duplicates DiagnosticSink; splitter/aggregator/
backpressure/message store/wiretap solve absent problems; PipelineObserver + ECS already
cover observability; pure JUnit tests stages without context.

**Reconsider when:** many routes (N sources → routing → M sinks), stage parallelism/
backpressure, durable mid-pipeline restart rather than whole-file retry, or distribution
across processes. None applies now.

<a id="4-si-остаётся-и-расширяется-только-на-edge-io-channel-adapter"></a>
### 4. SI remains and expands only at edge IO (Channel Adapter)

The sole production SI node is
[`IngestFlowConfiguration`](../../adapters/adapter-ingest/src/main/java/com/iocextractor/adapter/in/ingest/IngestFlowConfiguration.java):
FileReadingMessageSource (inbound adapter), optional WatchService, IngestFileListFilter
(stability quiet-period), Pollers.fixedDelay(reconcileInterval), `.handle()` (Service Activator
→ IngestSourceUseCase). It **does not touch row bytes**; passes a File reference into the pipeline.

This inbox flow **exemplifies** WatchService event + reconcileInterval correctness
backstop: events for latency, polling for reliability already exists. Extend SI only at IO edges:

- existing inbox detection;
- **RemoteSourceMonitor**, fetch's **remote twin** (polling + fingerprint behind SMB port),
  crossing the system boundary rather than transforming domain data.

> **Clarification A3:** existing remote fetch is **not SI-based** (DaemonFetchScheduler
> uses ScheduledExecutorService); decision 12 introduces shared PeriodicDaemonCycle.
> To avoid **two polling mechanisms**, Q9 **chooses PeriodicDaemonCycle**: SI offers no
> real reuse (FileReadingMessageSource cannot speak SMB, still needs custom MessageSource).
> SI is justified only paired with CHANGE_NOTIFY push, not introduced (decision 11).
> Terminologically the monitor moves metadata and emits a control fact: a detector
> at edge-IO/control-plane boundary, so decision 1 taxonomy is deliberately not strict here.

<a id="5-detection--execution--единый-каркас-реализует-ops-4"></a>
### 5. Detection ⊥ Execution — unified foundation (implements OPS-4)

Move detection out of executors. **Channel Adapters** feed events to the bus;
**Event-Driven Consumers** execute. Uniformity lives in **bus + consumer contract**,
without requiring all sources to push.

```text
[export-сага]            --emit SliceCompleted (after durable finish / program-order after commit)-->  ┐
                                                                  ├─►  ControlEventPublisher  ─►  Event-Driven Consumers
[RemoteSourceMonitor]    --emit RemoteChangeBatchDetected------>  ┘   (порт +         ├─ FetchExecutor   (RemoteFetchService)
   (monitor poll + fingerprint, тёплое соединение)                    ApplicationEventPublisher-адаптер)
                                                                                       ├─ PublishExecutor (ArtifactPublishService)
                                                                                       ├─ best-effort: metrics / cleanup / notify
```

- **Executors are command handlers, NOT polling consumers.** Each receives **specific work**,
  rather than “go discover what to do”:
  - **Publish:** change is **local, in the same JVM**, from export's completed slice.
    Genuine event-driven behavior is **free**: SliceCompleted →
    **`PublishCompletedSliceCommand(profile, sliceId, sliceName, manifestSha256)`** →
    exactly **one slice**. No fast-path publish(profile) scanning all slices. Need
    **`CompletedSliceCatalog.find(profile, sliceId)`**, verifying that unit's
    `_SUCCESS→manifest→files` chain rather than trusting it. Scan reconcile remains
    a **slow backstop**, worth making DB-driven (decision 7/R30).
    **Retention invariant (fast-path TOCTOU):** retention could delete a slice between
    event and find, silently losing publish (reconcile would also see empty FS).
    Therefore **retain any slice with at least one non-SUCCEEDED publish-ledger pair**;
    `PublishLedgerSliceRetentionGuard` must enforce this, not mere age/count (R31).
    **Precise emission point:** emit only for a genuinely available new slice, in
    ExportService **AVAILABLE→COMPLETED after ledger.finish**. **Not** general
    `ExportObserver.completed(...)`, also called for **SKIPPED** (discarded staging, no new
    slice; ExportService ~127/140). Unfiltered lifecycle-observer publication would
    falsely trigger publish. Keep control events separate from lifecycle observation.
  - **Fetch:** change occurs **on a non-pushing remote server**, so no cheap push.
    **RemoteSourceMonitor detects**: list + include/exclude + fingerprint diff, emits
    RemoteChangeBatchDetected with **specific work identities**. RemoteFetchService
    stops listing/filtering; becomes **“download these objects” command handler**
    (RemoteFetchWork), transport-neutral for future S3/SFTP too.
    **Fingerprint cache is a latency optimization, NOT truth.** Q4 shape: in-memory
    **RemoteObjectIdentity(path,size,mtime) set**, same ledger key; set-diff into bounded
    batches, no aggregate listing hash. V1 uses an **in-flight registry** shared by
    monitor/listener. Listener atomically claims identities immediately before keyed
    admission; monitor excludes claims next tick. Do not claim before publish: lost
    dispatch would suppress redetection. Release on success/failure/rejection; durable
    FETCHED removes completed work. Registry is in-memory, durable monitor state deferred.
    Restart may **re-emit** known identities; **ledger Idempotent Receiver** ensures
    safety. Correctness rests on ledger, not registry.

**Deliberate asymmetry.** Reject holding an open watcher session: idle SMB sessions
are precisely what expire (Read timed out). Smart polling reuses/heals connections,
rather than waiting on dead ones. True CHANGE_NOTIFY push deferred (decision 11).

<a id="6-словарь-control-событий-metadata-конверт-и-правило-размещения-издателя"></a>
### 6. Control-event vocabulary, metadata envelope and publisher placement

**ControlEvent metadata envelope.** Each event has minimal immutable metadata,
**not** broker headers/serialization; foundation for tracing/idempotency/future outbox:

| Field | Purpose |
|---|---|
| eventId | Unique event identity (event tracing/dedup, future outbox relay key), **NOT business idempotency** |
| eventType | Stable type for routing/filtering/metrics |
| eventVersion | Schema version included **immediately**, cheaply, for orderly future serialization/outbox migration |
| occurredAt | Fact time, injected Clock as elsewhere |
| correlationId | End-to-end correlation, **reuse ioc.run.id** already extended by 0011 to pipeline/fetch/publish cycles |
| causationId? | Optional parent event/command, run→slice→publish chain |

**eventId is not business idempotency.** Receivers deduplicate by **business keys**:
publish `(sliceId, targetId)` in PublishLedger, fetch RemoteObjectIdentity in fetch
ledger. eventId traces/deduplicates events for future outbox; repeating a fact with
the same business key stays safe. Optional explicit deduplicationKey is a seam,
never a ledger replacement.

YAGNI: keep envelope **minimal**, add broker metadata only with a real broker/outbox (decision 11).

**Cross-run correlation clarification (R35/C3).** SliceCompleted inherits the **export
run's ioc.run.id**, while publish is a separate operation. Consumer **inherits event
correlationId**, keeping run→slice→publish in one trace; causationId holds parent
 eventId/command. Do **not** invent another publish run.id and break fact→command tracing.

**Vocabulary:** facts with identifiers, not data, like 0012 coverage metadata.
Initial extensible set:

| Event | When | Carries | Publisher |
|---|---|---|---|
| IngestionFinished | File extracted into canonical + projected, after commit | run_id, source.id, counts | adapter (ingest saga), directly |
| SliceCompleted | Export closes slice (_SUCCESS, after commit) | profile, slice_id, revision | use case **through port** |
| RemoteChangeBatchDetected | Monitor detects listing delta | endpoint, source.id, batchId, **bounded** items, hasMore | adapter monitor → bridge |
| PublishCompleted | (slice,target) reaches SUCCEEDED | profile, slice_id, target_id | use case **through port** |

**Event = past-tense fact; command = imperative intent.** Thin **Fowler Event
Notification**: ID + envelope, consumer rereads through find. Prevent a disguised
command bus: events are accomplished facts (SliceCompleted, RemoteChangeBatchDetected,
PublishCompleted, IngestionFinished), commands imperative (PublishCompletedSliceCommand,
RemoteFetchWork). V1 **bans** intent pseudo-events PublishSliceRequested/FetchRequested
without a recorded fact. Always: *fact event* → subscriber decides → executor *command*.

**Bounded remote-delta batch, NOT large payload.** Thousands of remote files must not
become one data payload, violating thin-fact discipline. Emit bounded
`RemoteChangeBatchDetected(sourceId, batchId, items, hasMore)` with maxWorkItemsPerEvent;
hasMore=true means more batches follow. Bounds event memory and keyed execution.

**publish() placement determines port need:**

- Publisher **inside use case** (domain fact, e.g. SliceCompleted): framework-free
  ControlEventPublisher, since Spring is forbidden in core.
- Publisher **in adapter wiring** (SI transfer → IngestionFinished, monitor → batch
  detected): already in bootstrap, use the **same SpringControlEventPublisher bridge**,
  never raw ApplicationEventPublisher bypassing observer. Framework-free port optional
  here, but one publication point.

Do not port everything indiscriminately: framework-free port where publisher is domain-side;
shared bridge for all.

<a id="7-классификация-подписчиков-correctness-bearing-vs-best-effort"></a>
### 7. Subscriber classes: correctness-bearing vs best-effort

Subscribers **differ**, determining reliability requirements:

- **Correctness-bearing**, e.g. SliceCompleted publish trigger: cannot lose work.
  **Ledger reconcile**, not event, holds durability. Event fast path + periodic
  durable publish_ledger/remote_fetch_ledger reconcile provides at-least-once after
  crashes/lost events. Direct consequence of 0011 latency-hint doctrine.
- **Best-effort** (Prometheus metrics, temp cleanup, Telegram notifications): crash
  loss acceptable, no durable bus needed.

No outbox/DLQ just for metrics: correctness consumers already have reconcile.
Reduce reconciliation to a **rare** safety net (minutes), rather than the main engine.

**Make reconcile cheap, not merely infrequent — enabled by ING-4 (R30).** Both publish
and reconcile currently use listCompleted, decoding manifests and **rehashing all
files** of every slice. SQLite export_run/publish_ledger **already retain durable
slice metadata** (slice_name, manifest_sha256), enabling DB-driven reconcile, but
**no DB-backed CompletedSliceCatalog exists yet**: **E5 adds read models/queries**.
Backstop becomes **DB-driven**, pending-pair query O(pending), rather than
O(slices×files) rehash. New-slice **discovery** uses cheap directory listing + anti-join,
**rehashing only once** for newcomers; never read 0012 export tables (F3 filesystem
boundary). Removes expensive no-op IO on **both** paths, leaving one rehash per
slice at discovery (Q6 closed).

**Why not Spring Modulith Event Publication Registry?** It supplies durable publication
journal, at-least-once, staleness reconcile, restart republish, JDBC starter. Deliberately
**not adopted**: existing **business-key Idempotent Receiver ledgers** already supply
at-least-once/reconcile (`(sliceId,targetId)`, RemoteObjectIdentity, decision 6/R8).
An eventId journal adds **a redundant second durability layer**. Either way Registry
would be a **ControlEventPublisher delivery adapter**, outside platform-events
(decision 2a). Recorded under Rejected alternatives.

**Startup reconcile is mandatory, not just background backstop.** Strict startup:
**synchronous reconcile/backfill** (verified slices × targets → publish_ledger;
remote-fetch backlog), **then** fast-path listeners/monitors. Work accumulated during
downtime runs immediately rather than waiting a long interval. Generalizes existing
DaemonPublishScheduler.start() reconcile-before-loop to both directions; compatible
with export→publish→retention phase order.

<a id="8-транзакционная-привязка--publish-строго-после-commit-по-program-order-не-transactionaleventlistener"></a>
### 8. Transaction binding — publish strictly after commit by program order, NOT TransactionalEventListener

**Important technical review correction.** `@TransactionalEventListener(AFTER_COMMIT)`
requires an **active Spring transaction** at publication
([Spring documentation](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)):
without one, events are discarded unless fallbackExecution is enabled; fallbackExecution=true
+ async may run **before commit**. Here transactions stay **inside JDBC adapters**
(`TransactionTemplate.execute(...)` in `JdbcExportRunLedger.finish()` etc.), **without enclosing
use-case transactions**. By `controlEventPublisher.publish(...)` time commit has returned and no transaction
exists: listener without fallback executes **never**, with fallback executes immediately,
making phase meaningless. Therefore:

- **Program order provides correctness**: publish **strictly after** `ledger.finish(...)`/
  projection returns, intrinsically after commit. Ordinary **EventListener** performs
  synchronous publisher-thread handoff, then keyed executor handles heavy IO (decision 9).
- **TransactionalEventListener only where a real Spring transaction wraps publish**:
  nowhere now, potentially if transaction moves to use-case level.
- **State-change + publication crash atomicity** comes from **ledger reconcile**,
  not listener phase. Next reconcile repairs lost hints. **Gap verified covered** (Q5):
  atomic makeAvailable rename durably publishes `_SUCCESS` **before** COMPLETED commit/
  event; restart filesystem discovery finds it independently. True outbox/Modulith-like
  registry is a **deferred adapter-local seam** (outbox row in ledger transaction),
  not requiring use-case transactions (Q5, decision 11).
- “Publish after successful write” follows DDD publish-after-save discipline.

<a id="9-исполнение--keyed-single-flight-executor-не-просто-async"></a>
### 9. Execution — keyed single-flight executor, rather than just Async

ApplicationEventPublisher hands off to multicaster; it **promises neither asynchronous
nor immediate nor ordered execution**. Publish/fetch need **order** and **no overlap
per work key**, which a general async pool does not guarantee. Use an explicit abstraction.

**Three concerns (anti-broker protection, decision 2a/R27).** KeyedSerialExecutor owns
only (1) permanent per-key single-flight, protecting resources **even with a broker**;
(2) replaceable admission/queue seam, in-memory now/broker later, so work source is
injected, not hardcoded LinkedBlockingQueue; (3) **no redelivery/durability/DLQ**,
reconcile now/broker later. Executor is a **general concurrency primitive, not events**:
**outside platform-events**, either platform-concurrency or application port + bootstrap
implementation, preventing event-core execution creep (cohesion/SRP).

- **KeyedSerialExecutor/SingleFlightWorkExecutor**: sequential work **without overlap**
  per key, parallel across keys.
- **Key = actual transport contention unit, not entity ID (R28/B1).** SMB transport
  synchronizes **all operations per endpoint**; key is **endpoint**, not endpoint+path.
  Two paths still serialize, so finer keys suggest false parallelism. Publish → endpoint
  with targetId metadata; fetch → endpoint. Per-path parallelism is **unavailable in v1**,
  deferred until transport multiplexes. Single-writer rule: key identifies resource
  whose concurrent mutation breaks invariants. Generalizes global scheduler running CAS.
- **Protect execution boundary, not just listeners.** All local remote-publish paths
  (event, periodic reconcile/publish, possible manual/ops daemon bridge) use **one same
  executor**. Otherwise scheduler/fast path can overlap a pair: ledger CAS protects
  data, but false conflicts pollute health/telemetry. Read-only discovery may stay
  synchronous until handoff, runs **once per export profile**, not per target;
  retryable execution crosses the keyed boundary.
- **Never drop merely because key is busy.** Another SliceCompleted for targetId can
  mean **a new slice**. Contract: no overlap; **FIFO** or coalescing **with guaranteed
  rerun**, preserving work existence. Prefer **FIFO for slice-specific publish**;
  coalescing only for idempotent refresh hints such as reconcile ticks.
- **Shutdown/rejection is delivery semantics**: **bounded** per-key queue, graceful
  timed drain (existing STOP_TIMEOUT), then shutdownNow. Undrained/rejected work stays
  correct through **ledger reconcile**; log/diagnose rejection. Same discardable-fast-
  path/durable-backstop model. **Saturation admission (R32/B4, Q7): high/low-water
  hysteresis**, trigger `depth ≥ high-water` **OR** `oldest-age ≥ max-age` (decision 10 health).
  Above high-water, fast-path enqueue is **no-op**, DEBUG “shed to reconcile”, avoiding
  rejection churn. DB-driven reconcile drains ledger order; below low-water resumes.
  **Per key**, so a slow endpoint does not suppress others. Watermarks/queue size are
  configuration; **shedding rule code-owned**, observable in health/diagnostics.
  Slice specificity has little value under saturation; pending endpoint drain is
  natural durable-backstop load shedding.
- Framework-free contract in **platform-concurrency/application port, NOT events**
  (2a/R26); java.util.concurrent implementation in bootstrap.
- EventListener only **enqueues work and returns**; heavy SMB IO never blocks export/notifier.
- Work reuses existing **Retrier/RetryPolicy**, no new retry mechanism.
- **Propagate MDC/correlation** across executor thread handoff. Wrapper restores
  `ioc.run.id` (=correlationId), `event.id`, `event.type`, optional `causation.id?`
  during work through existing platform-observability MdcScope, then cleans up.
  Otherwise executor ECS loses fact→command correlation.
- **Consumer failure / poison policy: operator-visible failed saga state, NOT DLQ.**
  Never infinite retry or permanent key blockage. Bounded Retrier attempts, then ledger
  **FAILED/retryable**, diagnostic, **release item/key** so FIFO advances; reconcile/
  manual ops retries. This is saga state visible in health, **not message dead-letter**
  infrastructure. True Dead Letter Channel only with broker/outbox durability, an
  adapter seam (decision 11), never part of platform-events.

This resolves executor granularity: keyed FIFO, bounded queue, MDC propagation,
poison→ledger FAILED, reconcile backstop, rather than shared pool.

<a id="10-наблюдаемость-и-диагностика-шины--переиспользуем-стек"></a>
### 10. Bus observability and diagnostics — reuse the stack

- ControlEventObserver records **publication / dispatch / admission** through existing
  LogEvents/ECS. Reserve “delivery” for broker/outbox adapters; in-process has no delivery
  guarantee (decision 2). Events are changes, not no-ops, so **remove log spam structurally**:
  INFO only real facts/failures, routine detection no-ops DEBUG.
- Publication/dispatch errors through DiagnosticSink (possible `DiagnosticCategory.EVENTS` category or SYNC);
  event_publish/event_dispatch actions and ioc fields added **with producers**
  (completeness discipline, 0011/0003).
- **Operator-facing keyed-executor health/readiness** in ioc health/sync-health:
  **per-key queue depth**, **oldest-work age**, **rejected count**, **running keys**.
  Otherwise a stuck SMB publish looks like a live daemon while queue rots. Complements
  SyncHealthIndicator (last fetch/publish, pending/failed pairs).

<a id="11-yagni-seamы-сериализация-dlq-durable-outbox-change_notify--зарезервированы-не-строим"></a>
### 11. YAGNI seams: serialization, DLQ, durable outbox, CHANGE_NOTIFY — reserved, not built

> **Superseded note (2026-07-03):** the SMB2 CHANGE_NOTIFY part no longer describes
> current implementation. After a separate spike it landed as optional SMB adapter
> capability (RemoteChangeSignalSource) and fetch latency accelerator. The underlying
> ADR decision remains: events never became a broker, polling/reconcile remains the
> correctness backstop, delivery/watch lifecycle stays behind adapter/bootstrap boundaries.

Deliberately **not implemented now**. All are **delivery-adapter concerns outside
ControlEventPublisher**, not platform-events internals. Add an outbox/RabbitMQ/Kafka
adapter when needed, leaving event core unchanged (OCP). Design local port/model seams:

- **Serialization** only across process/persistence boundaries; in-process Spring events
  are objects. Activate with broker/outbox.
- **DLQ** only with durable queue/redelivery; failed in-process synchronous events simply
  throw. Reserve dead-letter-sink seam.
- **Durable outbox bus** if reconcile latency/cost becomes problematic for correctness
  consumers. Reconcile is current backstop (decision 7).
- **SMB2 CHANGE_NOTIFY**, true fetch push: **not introduced (Q9)**. Short fetch.interval
  smart polling on warm connection suffices without subsecond SLA. Push is **strictly
  additive** (server overflow loses events, polling stays), SMB-specific, adds fragile
  watch lifecycle (rearm+reconcile after failure) and keepalive against idle-session
  rot (same rejected watcher problem, decision 5). **Verify smbj support first**.
  If landed, host through SI `@InboundChannelAdapter` and revisit Q9. Entry criterion:
  measured latency SLA demonstrably impossible with polling.

<a id="12-границы-и-устранение-дублирования"></a>
### 12. Boundaries and deduplication

- **ArchUnit:** no `org.springframework.context.ApplicationEvent*`/ApplicationEventPublisher/EventListener in
  core; framework-free events port as with ETL/diagnostics. Spring bridge bootstrap-only.
- **DRY:** shared **PeriodicDaemonCycle**/executor scaffold via composition, **not
  lifecycle inheritance**, removes ~95% duplication between fetch/publish schedulers:
  CAS guard, single-thread executor, closeIdle finally, stop/phase, start/complete logs.
  Executors supply only work Runnable.

<a id="13-область-применимости--single-instance-v1-multi-instance--leasesfencing-seam"></a>
### 13. Applicability — single-instance v1 (multi-instance leases/fencing seam)

Explicit enterprise limit: executor guarantees single-flight **inside one JVM only**.
**V1 assumes one daemon instance.** Future HA/scaling needs service-DB **ledger leases /
fencing tokens / claims**, otherwise processes take the same pair/identity concurrently.
0012 partly prepares this (global DB partial index + OS recovery lease), but fetch/publish
do not yet use it. Cross-process coordination is **deferred**, not v1, documented to
avoid assuming cluster support.

<a id="модель-в-сборе"></a>
## Combined model

```text
                 ┌──────────────────────── bootstrap (Spring) ────────────────────────┐
 edge-IO (SI)    │  inbox FileReadingMessageSource ──► IngestSourceUseCase            │
                 │  RemoteSourceMonitor (monitor poll+fingerprint) ──┐                │
                 │                                              │ emit                │
 control-plane   │   SpringControlEventPublisher bridge (ApplicationEventPublisher,   │
                 │     @EventListener adapters, keyed executor, ControlEventObserver)     │
                 └───────────────▲───────────────────────────────────┬───────────────┘
                                 │ publish(ControlEvent) via порт     │ dispatch → keyed executor
 ┌─────────────── core/application (framework-free) ─────────────────┼───────────────┐
 │  use-cases ──► ControlEventPublisher.publish (publish-only порт)               ▼               │
 │  platform-etl (Stage/Envelope/PipelineRunner) ── transformation   FetchExecutor   (command handler)
 │  Retrier/RetryPolicy · *_ledger (Idempotent Receiver) · reconcile PublishExecutor (one slice/cmd) │
 └───────────────────────────────────────────────────────────────────────────────────┘
```

**Reuse map** (repository-level DRY):

| Need | Existing component | Use |
|---|---|---|
| retry/backoff | Retrier/RetryPolicy (application/sync) | executor invokes existing |
| idempotent receive | RemoteFetchLedger/PublishLedger | Idempotent Receiver + reconcile |
| transformation | platform-etl stages | unchanged core |
| observability | LogEvents/ECS/PipelineObserver | ControlEventObserver over existing |
| diagnostics | DiagnosticSink | delivery failures |
| edge polling example | inbox FileReadingMessageSource | RemoteSourceMonitor template |

<a id="применимость"></a>
## Applicability

1. **Immediate sync hardening = OPS-4.** Monitor smart polling/fingerprint emits batches;
   FetchExecutor downloads only delta. Export SliceCompleted triggers PublishExecutor;
   reconcile becomes rare backstop, no per-tick rehash. Structurally quiet logs (decision 10).
2. **Reuse, the main motivation.** New cross-cutting consumers (metrics, cleanup,
   administrator Telegram report) become **independent best-effort subscribers**,
   without pipeline changes: no reinvention of subscription/logging/error handling.
   Each connector has its own adapter only when needed; build seams, not connectors (YAGNI).
3. **Future sagas**, such as backlog STIX/OpenIOC export sink, reuse events and reconcile.

<a id="следствия"></a>
## Consequences

- New **platform-events**: ControlEvent envelope, publish-only publisher, Noop/Recording
  doubles, observer, package-info/README disclaimer (not durable bus/broker/routing).
  Parent dependencyManagement; events-only, no external libraries. **Executor outside**
  events, platform-concurrency or application port (2a/9). **No EventSubscriber SPI**:
  consume seam = command.
- **Bootstrap:** ApplicationEventPublisher adapter, listener driving beans, executor
  implementation, RemoteSourceMonitor on **PeriodicDaemonCycle** (Q9), shared cycle.
  TransactionalEventListener only with real Spring tx (now nowhere).
- **Application:** publish facts through port **after** ledger/projection returns
  (decision 8). Fetch/publish become **single-work command handlers**, not polling
  consumers; scan reconcile remains safety net. Add CompletedSliceCatalog.find;
  record facts beside bounded areas.
- **Observability/diagnostics:** `event.action` values event_publish/event_dispatch, `ioc.*` fields,
  possible `DiagnosticCategory.EVENTS` category; regenerate catalog when codes appear.
- **ArchUnit:** no Spring events in core; events remains framework-free **and anti-broker**:
  no queue/broker/serialization, no executor, no Serializable until broker exists.
- **Docs:** architecture (three planes), ingestion/sync (event flow), logging/diagnostics
  (taxonomy), dev index; close/redirect **OPS-4** to this ADR in techdebt.
- Fetch/publish schedulers collapse into shared scaffold + thin subscriber executors.

<a id="план-реализации-по-срезам"></a>
## Implementation plan by slice

Each slice is a commit with affected directory READMEs and invariant/logic tests.
Order: framework-free foundation, bridge/observability, migrate sync directions,
edge monitor and final gate.

- **E0: platform-events contract.** ControlEvent envelope, publish-only port,
  Noop/Recording publishers, observer, framework-free/anti-broker ArchUnit (no queues/
  serialization/Serializable), README disclaimer. **No executor contract here**: general
  concurrency goes to E0a (2a/9). **TCK-style contracts**, like IngestionLedger TCK in `ioc-application-tck`:
  fire-and-observe, observer-called-on-failure. **No subscriber SPI**, consume seam is
  command. Commit: `ARCH: introduce control event publisher port`.
- **E0a: platform-concurrency.** KeyedSerialExecutor/SingleFlightWorkExecutor contract:
  per-key FIFO/no-overlap, admission seam, **endpoint key**, framework-free. TCK checks
  same-key no-overlap/FIFO, different-key parallelism, shutdown drain, observed rejection.
  **No durable redelivery/DLQ**. Commit: `ARCH: introduce keyed single-flight executor`.
- **E1: bootstrap Spring bridge.** Single SpringControlEventPublisher for core/adapter
  events; EventListener → commands; bounded FIFO executor/graceful drain; observer→ECS.
  **Bridge runs E0 TCK, executor runs E0a TCK**. Test program-order after-commit,
  nonblocking handoff, MDC/correlation, poison→FAILED+release, queue health.
  **No TransactionalEventListener**, no enclosing tx.
  Commit: `FEATURE: bridge event bus to Spring`.
- **E2: shared execution scaffold.** PeriodicDaemonCycle composition, overlap/closeIdle/
  stop/phase extraction; no behavioral change. Commit:
  `REFACTOR: extract periodic daemon cycle`. **Sequencing C2:** E3/E4 immediately
  reduce periodic fast path to rare reconcile; design abstraction **for its final role**,
  separate ReconcileCycle from keyed-executor driver, not periodic-everything.
- **E3: slice-specific event-driven publish.** Emit SliceCompleted **only in
  AVAILABLE→COMPLETED**, not shared observer called for skipped; after commit → command →
  find → one slice through keyed executor. Fast path touches one slice, no per-tick rehash.
  Test skipped does not publish, lost event repaired by reconcile.
  Commit: `FEATURE: drive publish by slice-completed events`.
- **E4: fetch detection/execution split.** Monitor polling/filter/fingerprint emits
  bounded batches; fetch handler downloads supplied identities without list/filter;
  ledger backstop. Commit: `FEATURE: split remote fetch detection from execution`.
  **Endpoint key**, **PeriodicDaemonCycle** monitor (Q9); no CHANGE_NOTIFY/SI (decision 11).
- **E5: DB-driven reconcile, observability, log levels, docs, gate.** Pending ledger
  queries O(pending); discovery listing+anti-join+rehash-once; retain F3 boundary
  (7/R30/Q6). **Resilient discovery (R36):** one corrupt/partial/leftover child under
  `root/<profile>/` must **not abort** the profile. Skip+quarantine+diagnose; current `FileSystemCompletedSliceCatalog.listCompleted`
  throws on first invalid child, blocking backstop, which must survive dirty states.
  Actions/fields/diagnostics, routine INFO→DEBUG, published docs, OPS-4 closure.
  E2E: event→handler, lost-event recovery, after-commit, keyed single-flight,
  **no per-tick reconcile rehash**, **corrupt child cannot block profile reconcile**.
  Commit: `TEST: gate event-driven coordination end to end`.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

<a id="открытые-вопросы"></a>
## Open questions

1. ✅ **Event vocabulary placement resolved (decision 2).** Base ControlEvent/infrastructure
   in platform-events; concrete records beside application bounded areas.
2. ✅ **Reuse SYNC in v1**, producer-with-code discipline. No actual delivery-failure
   codes now (publish fire-and-observe; executor failures SYNC, since only sync consumers).
   Introduce **EVENTS only with first cross-domain non-sync consumer** needing codes
   outside SYNC. Until then EVENTS is a seam, not category.
3. ✅ **Executor granularity/key resolved (9/R28).** Keyed single-flight, **endpoint v1**,
   real SMB serialization unit; endpoint+remotePath only after transport multiplexing.
   Not general Async pool.
4. ✅ **Fingerprint path/size/mtime set + set-diff** (decision 5). In-memory
   RemoteObjectIdentity matches fetch ledger; no new identity. Bounded batches (R19),
   durable ledger dedup. **No aggregate listing hash**: negligible gain while listing
   every tick and trivial set diff. Content-hash detection is a **seam** (no cheap SMB
   server hash, same 0011 verification limit). Cap/LRU deferred for huge directories.
5. ✅ **Do NOT raise tx to use case** (7–8): conflicts with stepwise CAS saga/recovery
   rather than one large tx; only speeds rare crash recovery **already covered by
   reconcile** (YAGNI); future outbox is **adapter-local**, terminal+outbox row in same
   ledger tx + relay. **Code verifies commit↔event gap**: makeAvailable atomic rename
   durably publishes marker **before** completed commit/event; restart listCompleted
   (filesystem) → ensurePending → publish, independent of event. Reconsider only for
   genuine exactly-once **non-idempotent side effects** impossible to represent by
   business-key Idempotent Receiver.
6. ✅ **DB-driven reconcile** (7/R30): known pairs from publish_ledger,
   `status NOT IN (SUCCEEDED, ABANDONED)`, O(pending), no FS access. Discovery for a
   lost event + crash before ensurePending: cheap listing/anti-join, rehash only newly
   unregistered slices; **no export-table reads**, retain F3 filesystem boundary.
   Remove rehash **entirely from periodic slow path**, not merely reduce frequency.
   E3 adds find, E5 adds DB reconcile/listing discovery. R31 retention handles DB↔FS drift.
7. ✅ **Saturated-key admission** (9/R32): high/low-water hysteresis, depth≥high-water
   **OR** oldest-age≥max-age. Above high, no-op enqueue with DEBUG shed-to-reconcile,
   no rejection churn; DB reconcile drains ledger order; below low resumes. **Per key**,
   no endpoint-wide suppression of others. Configured thresholds/size, **code-owned
   shedding rule**, observable health flag + diagnostic, avoiding hidden stuck targets.
8. ✅ **Modulith buy-vs-build** (7/Rejected): not adopted; ledgers already provide
   business at-least-once. Registry is potential delivery adapter, never core.
9. ✅ **PeriodicDaemonCycle monitor**: one polling mechanism. SI cannot reuse local
   FileReadingMessageSource for SMB, still needs custom MessageSource. **Short
   fetch.interval, 5–15s**, cheap warm listing, sufficient without strict subsecond SLA.
   **SI and CHANGE_NOTIFY deferred together** (11); InboundChannelAdapter justified
   only when/if push lands.

<a id="отклонённые-альтернативы-сводно"></a>
## Rejected alternatives (collected)

- **Spring Integration ETL core** (3): library-vs-framework IoC, dependency violation
  in (b), useless wrapping in (a), unused extras in linear single-thread flow.
  Consistent with Camel (0011)/Spark (0012) rejection.
- **SI MessageChannel domain bus** (2): extra SI surface for simpler built-in Spring publication.
- **Spring events directly in application** (12): violates framework-free core; port + bootstrap adapter.
- **Open SMB watcher session** (5): idle rot; smart polling more robust, true push a separate option (11).
- **Durable outbox/DLQ/serialization now** (7/11): ledger reconcile holds correctness;
  YAGNI until real need, seams reserved.
- **True CHANGE_NOTIFY push v1** (11): fragile SMB-specific lifecycle, latency optimization
  not correctness. **Superseded 2026-07-03:** separate optional SMB capability implemented
  after spike; polling/reconcile retained.
- **Homemade platform-events mini-RMQ** with durable queue/redelivery/DLQ/serialization/
  routing (2/11): core defines model/publish contract, delivery mechanics live behind
  publisher adapter; building broker overcomplicates and breaks OCP.
- **Modulith Registry as durability layer** (2a/7), seriously considered (cheap JDBC
  starter after ING-4, framework-free boundary intact with bootstrap adapter), rejected
  for **at-least-once granularity**:
  - Modulith guarantees an **event** (eventId; COMPLETED = listener returned); our
    correctness unit is **(slice,target)** with SUCCEEDED = remote marker verified.
    One SliceCompleted expands into **N pairs**.
  - **Partial target failure**: A succeeds, B unavailable; listener returns, event
    completed, Modulith **does not redeliver B**. Only ledger reconcile
    (ArtifactPublishService.publishRetryable) can retry `(slice, B)`.
  - **Ledger reconcile is mandatory anyway** for partial failures; it **also** repairs
    lost triggers via completed on-disk slices. Event durability is **redundant**, not
    simply unnecessary in isolation.
  - Thus Modulith is **additive, not replacing**: registry **plus** same reconcile,
    more machinery.

  Two extra drawbacks: **(1) not drop-in**: Registry requires publication inside an
  enclosing tx, currently adapter-local (8), forcing use-case tx changes (Q5).
  **(2)** `@ApplicationModuleListener` uses **shared async pool without per-key order**,
  does not solve endpoint overlap, still needs executor (9), potentially worsens it.
  **Reconsider only if Q5 changes** (true atomicity needed) **or Q6 changes** (DB-driven
  reconcile still costly); then delivery adapter behind publisher (2a).
- **No bus, direct listener ports** (SliceCompletionListener/RemoteChangeListener):
  detection/execution separation and async handoff possible with ports + executor
  alone, but remove the **broker-ready platform seam** that should survive broker
  integration (2a), and shared observability/extensibility for metrics/cleanup/notify.
  Async/decoupling justify **executor**; **broker readiness + fan-out** justify bus
  contract. Keep platform, disciplined and thin (2a).

> Related: [0011](0011-remote-sync.md) (implements Post-v1 clarification and OPS-4),
> [0012](0012-streaming-dataframe-emission.md) (SliceCompleted source),
> [0005](0005-services-and-pipeline.md) (ETL), [0007](0007-logging-observability.md)
> (ECS/observability), [../techdebt.md](../KNOWN-ISSUES.md) (OPS-4).

<a id="операционный-план-реализации"></a>
## Operational implementation plan

Practical execution plan: small verifiable states, commits between slices; keep
platform foundation, publish/fetch behavior and final docs separate. Multiple slices
in one run still require intervening commits.

Before implementation, freeze ADR in a separate doc commit or explicitly exclude it
from staging and commit code via explicit paths. Implementation commits must not
accidentally absorb unfinished design edits.

<a id="s0--module-skeleton-and-guardrails"></a>
### S0 — module skeleton and guardrails

**Goal:** introduce platform locations and immediate architectural guardrails.

**Changes:**

- Add platform-events and platform-concurrency Maven modules to parent `pom.xml`.
- New module `README.md`/`package-info.java`; update `platform/README.md`.
- Extend `bootstrap/ioc-app/.../ArchitectureTest.java`:
  - framework-free events;
  - anti-broker: no Spring, queue, broker, serialization/wire-format or routing framework;
  - executor/single-flight primitives outside events;
  - no Serializable ControlEvent until real delivery/broker adapter;
  - no Spring event types in core.

**Checks:**

```bash
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Commit:** `ARCH: add event coordination platform modules`.

<a id="s1--platform-events-contract"></a>
### S1 — platform-events contract

**Goal:** thin model + publish-only port, no delivery mechanics.

**Changes in platform-events:**

- ControlEvent minimal application event marker/contract;
- immutable ControlEventMetadata: eventId, eventType, eventVersion, occurredAt,
  correlationId, optional causationId;
- ControlEventPublisher publish-only;
- ControlEventObserver observes publication/dispatch without becoming handler;
- Noop/Recording publishers for tests/default wiring.

**Excluded from this slice:**

- EventSubscriber SPI;
- durable queue;
- retry/redelivery/DLQ;
- routing/subscription matching;
- serialization/wire format;
- all Spring types.

**Tests:**

- metadata validation, immutability/required fields;
- noop does not fail;
- recording preserves publication order;
- observer failure cannot break business flow if fire-and-observe contract chosen.

**Checks:**

```bash
./mvnw -pl platform/platform-events -am test
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Commit:** `ARCH: introduce control event publisher port`.

<a id="s2--platform-concurrency-keyed-single-flight"></a>
### S2 — platform-concurrency keyed single-flight

**Goal:** separate concurrency/ordering from event model into a general primitive.

**Changes in platform-concurrency:**

- KeyedSerialExecutor or SingleFlightWorkExecutor contract;
- key/admission-result value types only if needed;
- framework-free java.util.concurrent implementation, or contract + bootstrap implementation.

Recommendation: pure JDK implementation in platform-concurrency is not an external-library
adapter and is easier to test. Bootstrap only configures lifecycle/queue parameters.
If lifecycle proves too Spring-dependent, implementation may remain bootstrap-side;
contract must still never move into events.

**Semantics:**

- same-key no-overlap and FIFO;
- different keys parallel;
- bounded admission;
- high/low-water seam;
- graceful shutdown/drain;
- no durable redelivery/DLQ.

**V1 key:** endpoint; endpoint+remotePath deferred until actual transport multiplexing.

**Tests:**

- same-key tasks never overlap and retain order;
- different keys can run concurrently;
- saturated key uses admission/no-op without affecting other keys;
- shutdown leaves no uncompleted/unobserved running task.

**Checks:**

```bash
./mvnw -pl platform/platform-concurrency -am test
./mvnw -pl bootstrap/ioc-app -Dtest=ArchitectureTest test
```

**Commit:** `ARCH: introduce keyed single-flight executor`.

<a id="s3--spring-bridge-in-bootstrap"></a>
### S3 — Spring bridge in bootstrap

**Goal:** current delivery adapter behind publisher without Spring in core.

**Bootstrap changes:**

- SpringControlEventPublisher over ApplicationEventPublisher;
- composition-root bean configuration;
- observer bridge to ECS/diagnostics;
- recording/noop test wiring for modes without events;
- EventListener only in bootstrap-facing beans.

**Invariants:**

- application depends on events, never Spring;
- events are facts, not commands with business data;
- observer failures cannot break use case;
- listeners invoke command handlers, contain no business logic.

**Tests:**

- bridge publishes;
- observer sees publication/dispatch telemetry;
- ArchUnit no core Spring types;
- handoff blocks publisher no longer than delivery mechanism requires.

**Checks:**

```bash
./mvnw -pl bootstrap/ioc-app -am test
```

**Commit:** `FEATURE: bridge control events to Spring`.

**Post-implementation S0–S3 notes:**

- Bridge remains **fire-and-observe**: publisher/synchronous listener handoff errors
  observed, not propagated to producer.
- publishFailed/dispatchFailed are operator **ERROR/WARN**, never DEBUG; swallowed
  dispatch must not become silently lost until reconcile.
- Executor already has rejected/failed/dispatchRejected telemetry, but **high/low-water
  hysteresis policy is not implemented yet**. Before S5/S7, decide shed entry/recovery
  and health/log signals.
- REJECTED/dispatchRejected cannot dead-end: caller routes to backstop and visible
  state; executor remains in-memory, not durable delivery.
- Future listener/enqueue beans **daemon-gated or no-op** where background delivery
  must not start. Oneshot/manual export must not unexpectedly enqueue fetch/publish.

<a id="s4--periodicreconcile-cycle-extraction"></a>
### S4 — periodic/reconcile cycle extraction

**Goal:** remove duplicated lifecycle before event fast path.

**Current code:**

- Fetch scheduler fixed-delay, AtomicBoolean overlap guard, closeIdle, stop timeout.
- Publish scheduler nearly identical.

**Changes:**

- Extract PeriodicDaemonCycle or narrower ReconcileCycle.
- Keep phase/health/logging in thin executors.
- Preserve behavior before event migration.
- Avoid universal scheduler: after E3/E4, backstop/reconcile rather than fast path.

**Tests:**

- preserve non-overlap;
- closeIdle after cycle;
- source/target failure does not stop others;
- stop terminates executor correctly.

**Checks:**

```bash
./mvnw -pl bootstrap/ioc-app -am test
```

**Commit:** `REFACTOR: extract periodic daemon cycle`.

<a id="s5--publish-fast-path-by-slicecompleted"></a>
### S5 — publish fast-path by SliceCompleted

**Goal:** targeted event publish replaces normal full scan.

**Current code:**

- `ExportObserver.completed(terminal)` invoked for both completed and skipped;
- `ArtifactPublishService.publish()` traverses `selectedProfiles -> listCompleted(profile) -> targets`;
- catalog only supports listCompleted.

**Changes:**

- Concrete SliceCompleted beside application bounded area.
- Inject publisher into ExportService.
- Emit only after durable AVAILABLE→COMPLETED; none for skipped.
- Application command handler: PublishCompletedSliceCommand(profile, sliceId,
  correlationId/causationId...), use-case method for one slice.
- Add catalog find(profile,sliceId), `FileSystemCompletedSliceCatalog` implementation without profile scan.
- Bootstrap listener expands profile targets, enqueues by endpoint.
- Periodic publish uses **same endpoint executor**; may synchronously await work
  completion, never bypass single-flight for remote publish.
- Daemon-capable wiring only; oneshot/manual export may emit facts, background enqueue no-op.
- Handle WorkAdmission.REJECTED/dispatchRejected as shed-to-reconcile:
  - no listener retry loop;
  - operator-visible health/log;
  - S6 ledger reconcile holds correctness.

**Invariants:**

- no rows/bytes/business payload, only slice identity/reference;
- idempotency via `publish_ledger.ensurePending`;
- lost event recovered later.

**Tests:**

- COMPLETED emits SliceCompleted;
- SKIPPED does not;
- listener creates work for profile targets;
- listener inactive where background publish forbidden;
- saturated/rejected key preserves correctness, shows shedding;
- repeat event deduplicated by ledger;
- find validates one slice, no full scan.

**Checks:**

```bash
./mvnw -pl core/ioc-application,adapters/adapter-sink-csv,bootstrap/ioc-app -am test
```

**Commit:** `FEATURE: drive publish by slice completed events`.

<a id="s6--db-driven-publish-reconcile"></a>
### S6 — DB-driven publish reconcile

**Goal:** ledger-state safety loop instead of regular filesystem rehash.

**Current code:**

- `ArtifactPublishService.reconcile()` calls `CompletedSliceCatalog.listCompletedSliceNames` once per profile and anti-joins ledger;
  publish runs retryable rows;
- `JdbcPublishLedger.findRetryable()` selects `PENDING`/`FAILED`;
- new-slice discovery still depends on full catalog scan.

**Changes:**

- Separate single-slice fast path from backstop.
- DB-driven known-pair reconcile: pending, failed, and optionally stale `IN_PROGRESS`
  under a separate recovery rule.
- Rare/cheap discovery: directory listing, ledger anti-join, verify/rehash only new slices.
- No 0012 export-table reads; filesystem boundary retained.
- One corrupt/partial directory must not block profile: skip, diagnostic, optional
  quarantine/marker under agreed policy.

**Tests:**

- retryables queried without reading every slice;
- discovery fills missing slice/target pairs;
- corrupt slice does not abort profile;
- lost SliceCompleted recovered;
- no per-tick rehash for known ledger pairs.

**Checks:**

```bash
./mvnw -pl core/ioc-application,adapters/adapter-store-jdbc,adapters/adapter-sink-csv,bootstrap/ioc-app -am test
```

**Commit:** `FEATURE: reconcile publish from ledger state`.

<a id="s7--fetch-detectionexecution-split"></a>
### S7 — fetch detection/execution split

**Goal:** separate monitor/detection from RemoteFetchService execution.

**Current code:**

- `RemoteFetchService.fetch()` lists, filters include/exclude, downloads;
- scheduler invokes fetch per source;
- ledger holds correctness, responsibilities remain mixed.

**Changes:**

- RemoteSourceMonitor: list, include/exclude, path/size/mtime fingerprint set,
  set-diff, bounded batch, RemoteChangeBatchDetected.
- Application FetchRemoteObjectsCommand handler with work identities.
- Fetch service downloads supplied work without listing.
- Preserve CLI/manual behavior as reconcile/manual path or adapt through monitor.
- Bootstrap listener enqueues by endpoint.
- Shared process-local registry claims identities before admission, releases on
  success/failure/rejection, preventing slow-endpoint duplicates each tick.
- PeriodicDaemonCycle monitor, no SI.
- Before enabling listener, high/low-water policy over `KeyedSerialExecutorObserver`:
  `depth >= high-water` or `oldest-age >= max-age` sheds; below low-water resumes.

**Invariants:**

- no file contents in event;
- ledger remains Idempotent Receiver/backstop;
- no CHANGE_NOTIFY/SI push v1;
- saturated endpoint can shed without correctness loss.

**Tests:**

- only new/changed identities emitted;
- existing filter semantics;
- bounded split of large batch;
- repeated detection never redownloads FETCHED;
- per-endpoint listener serialization;
- saturation sheds without busy retry or lost ledger correctness.

**Checks:**

```bash
./mvnw -pl core/ioc-application,bootstrap/ioc-app -am test
```

**Commit:** `FEATURE: split remote fetch detection from execution`.

<a id="s8--observability-health-docs-and-final-gate"></a>
### S8 — observability, health, docs and final gate

**Goal:** close operational seams, publish the new model.

**Changes:**

- ECS actions for publication, dispatch, keyed admission/shedding, reconcile recovery.
- Routine empty ticks INFO→DEBUG unless meaningful to operator.
- Health/actuator: per-key queue depth, running keys, oldest age, shed flag,
  last dispatch failure.
- Observer rejected/failed/dispatchRejected visible through ECS/health without DEBUG.
- SYNC diagnostics for v1; no EVENTS until non-sync consumer with its own codes.
- Published docs: `docs/architecture.md`, `docs/ingestion.md`/sync sections,
  `docs/logging.md`, `docs/diagnostics.md`, `docs/dev/README.md`, `docs/techdebt.md`/OPS-4.

**E2E/regression checks:**

- SliceCompleted → publish command handler;
- reconcile repairs lost event;
- skipped does not publish;
- no event/periodic overlap per endpoint;
- corrupt slice cannot block profile;
- framework-free core;
- anti-broker events.

**Final gate:**

```bash
./mvnw -B -ntp -T 1C verify
```

**Commit:** `TEST: gate event-driven coordination end to end`.

<a id="рекомендуемое-разбиение"></a>
### Recommended grouping

- **Run 1: S0–S3.** Foundation, events, single-flight, Spring bridge. Low business
  regression risk, establish ArchUnit immediately.
- **Run 2: S4–S6.** Publish: cycle extraction, SliceCompleted, targeted publish,
  DB reconcile. Most sensitive: export completion, filesystem catalog, publish ledger.
- **Run 3: S7–S8.** Fetch split, observability/health, docs, full verify.

Multiple slices may execute in one run; commits follow S-state boundaries strictly.
If architecture diverges mid-run, update ADR/plan first, then code, never reverse.
