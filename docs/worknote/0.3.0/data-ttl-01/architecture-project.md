---
title: "DATA-TTL-01 — architecture project"
version: "0.3.0"
status: "Draft for review"
document_type: "Architecture project"
source_of_truth: false
language: "en"
---

# DATA-TTL-01 — architecture project

## 1. Executive decision

DATA-TTL-01 should be implemented as a **canonical artifact record lifecycle**,
not as a background `DELETE` job and not as a source-owned IOC timeout. The
stored duration is policy; the durable fact used by reads is an absolute
expiration deadline for one concrete canonical row lifecycle.

The recommended V1 shape is:

- framework-free lifecycle policy, values and use cases stay in
  `core/ioc-application`, under a distinct `artifact.lifecycle` capability;
- SQLite lifecycle state, atomic confirmation/expiration SQL, history,
  allocators and recovery markers stay in the existing
  `adapter-store-jdbc` integration family;
- CSV remains a derived projection behind an application port;
- Spring configuration, startup admission, scheduling and Actuator mapping stay
  in `bootstrap/ioc-app`;
- ingestion receives a durable observation identity and a bounded prepared-row
  receipt, so the same content can be a new freshness confirmation without
  making crash retry extend TTL twice;
- in-process events are used only as post-commit latency hints. Durable SQLite
  state plus startup/periodic reconciliation remains the correctness authority;
- expiry does **not** advance the existing insert-driven `artifact_revision`
  and does not create an immutable export slice by itself;
- no new Maven module, third-party scheduler or separately published Java
  library is justified for V1.

The repository is already a multi-module reactor: its core, platform and adapter
artifacts are ordinary JARs, while only `bootstrap/ioc-app` assembles the
executable fat JAR. A new module would therefore be an additional architectural
boundary, not a prerequisite for internal reuse.

This document is the design input for review. ADR-0020 remains a draft until
this project and its unresolved implementation parameters are accepted.

## 2. Scope and evidence

This project is based on:

- accepted interview outcomes I-01 through I-20 in [discovery.md](discovery.md);
- the release outcome and exclusions in [release-contract.md](release-contract.md);
- the current implementation of canonical writes, mutable projection,
  immutable export, ingestion recovery, SQLite schema reconciliation and daemon
  startup;
- the project's clean hexagonal/module rules.

It designs service-side data freshness and data quality. It does not design DNS,
firewall or other downstream target lifecycle protocols.

### 2.1 In scope

- fixed configurable TTL for every accepted canonical artifact row lifecycle;
- exact active reads, renewal, expiry, history and reappearance;
- public/internal/source identity separation and non-reuse;
- mutable dataframe convergence after expiry;
- preservation of the accepted immutable export trigger behavior;
- daemon and stateful oneshot admission;
- clock safety, recovery, health and aggregate observability;
- legacy 0.2.0 activation and rollback boundary;
- a bounded no-ETL path for repeated source content;
- the 100,000 simultaneously due validation envelope.

### 2.2 Explicitly out of scope

- per-source, per-IOC-type or risk-scored TTL policies;
- negative evidence from absence in an incomplete feed;
- public `expires_at` or population of public `time_first_seen` and
  `time_last_seen`;
- downstream acknowledgement, revoke or delivery state;
- manual bulk mutation commands;
- retroactive deadline recalculation;
- event sourcing, an external broker, distributed leases or multi-daemon
  fencing;
- an indefinite no-ETL guarantee for repeated sources.

## 3. Requirements traceability

| ID | Business requirement | Architectural consequence |
|---|---|---|
| `BR-01` | TTL belongs to one concrete DB record lifecycle | Lifecycle columns are co-located with the active artifact row; source and IOC taxonomy are not owners |
| `BR-02` | Only a successful canonical commit confirms freshness | Confirmation is part of the atomic canonical write port after the failure-policy checkpoint |
| `BR-03` | Absence from a later document is not revocation | No snapshot-diff deletion and no source withdrawal logic in V1 |
| `BR-04` | Every accepted observation renews a still-active row | V1 writes `lastConfirmedAt` and `expiresAt = asOf + fixedTtl`; no near-deadline threshold optimization |
| `BR-05` | At the exact deadline the row is inactive | Every active read applies a half-open `expiresAt > asOf` predicate |
| `BR-06` | Observation after expiry is a new record lifecycle | Old state is archived; a new lifecycle and new service-owned ID are allocated |
| `BR-07` | Service IDs are never reused; source IDs are separate | Durable allocators survive deletion/restart; source IDs remain namespaced provenance |
| `BR-08` | Fresh production installs use fixed `12h` | Packaging explicitly overrides the compatibility default |
| `BR-09` | Existing installs opt in destructively | Persisted `ACTIVATING -> ACTIVE` state and named `existing-records: expire` policy |
| `BR-10` | Duration changes are prospective | Stored absolute deadlines are not recomputed until a later accepted observation |
| `BR-11` | Public schemas stay stable | Technical timestamps stay internal; public `time_*` remain `NULL` |
| `BR-12` | Mutable dataframe must converge quickly | Active-only reads plus separate durable projection generations and a nudge/backstop worker |
| `BR-13` | Expiry alone does not export | Existing `artifact_revision` remains insert-driven and is isolated from lifecycle projection state |
| `BR-14` | History keeps a full row and compact sources for `30d` | Typed per-artifact history plus bounded retention |
| `BR-15` | Downtime counts against TTL | Absolute UTC deadline, startup reconciliation and durable clock high-water |
| `BR-16` | Clock rollback is unsafe beyond tolerance | Clamp-and-degrade for a bounded correction; fail-closed `DOWN` beyond policy |
| `BR-17` | Healthy idle reconciliation starts within `5s` | Nearest-deadline scheduling plus a five-second-or-faster periodic backstop |
| `BR-18` | All 100,000 rows may expire together | Indexed discovery, keyset batches, bounded transactions and coalesced projection |
| `BR-19` | Repeated identical content is a new observation | Source content identity is separated from delivery/observation identity |
| `BR-20` | No-ETL duplicates are bounded | Complete fingerprinted prepared-row receipts expire after `30d`; fallback is ordinary ETL |

## 4. Current architecture and gaps

### 4.1 Reusable seams already present

The current design provides useful boundaries rather than requiring a parallel
TTL subsystem:

- `WriteArtifactsStage` runs after the failure-policy checkpoint;
- `CanonicalArtifactRepository` hides canonical persistence;
- `ArtifactProjection` hides mutable CSV materialization;
- `SnapshotSliceReader` owns one multi-artifact SQLite read snapshot;
- `RunLedger` recovers the ingest write-to-projection saga;
- `ControlEventPublisher` already expresses lossy post-commit hints;
- `platform-concurrency` already provides keyed single-flight primitives;
- `DataframeFormatMigrations` owns stable format tables, while
  `DataframeSchemaReconciler` owns configured per-artifact tables;
- `IngestionStartupCoordinator` already enforces recovery before intake.

### 4.2 Gaps that make a standalone delete job unsafe

| Current behavior | TTL failure if unchanged |
|---|---|
| `load()` and snapshot export read every physical row | Due rows leak until cleanup finishes |
| Public IDs restart from active `MAX(id)+1` | Deleting the highest row can reuse its public ID after restart |
| `ArtifactWritePlan` reserves IDs in process memory | Reservation is not authoritative across restart/processes |
| Each row currently calls `clock.instant()` independently | One transaction can contain inconsistent confirmation times |
| `artifact_revision` changes only on insert | It cannot also represent expiry-driven mutable projection work without changing export semantics |
| CSV projection has no independent durable generation | Crash after expiry can leave the dataframe stale indefinitely |
| A terminal `source_key` suppresses the same content forever | A legitimate later observation cannot renew or recreate records |
| `ingest_run` is marked DB-committed after canonical commits | A crash in between can replay the same observation and extend TTL twice |
| Export scheduler starts as `SmartLifecycle`, intake recovery is an `ApplicationRunner` | Export can observe legacy/due state before lifecycle admission completes |
| Technical timestamps are ISO text today | Variable fractional ISO strings are unsuitable as a guaranteed numeric range-key contract |

The last point is important: lifecycle deadline comparisons and indexes should
use one explicit integer precision, not rely on lexical ordering of serialized
`Instant` strings.

## 5. Quality-attribute scenarios

| Attribute | Required scenario | Design response |
|---|---|---|
| Correctness | A read starts exactly at `expiresAt` before physical cleanup | `expiresAt > asOf` excludes the row |
| Atomicity | Confirmation races the expiry worker | SQLite write serialization produces either renew-before-close or close-then-new-lifecycle; no mixed state |
| Idempotency | Process crashes after artifact commit but before service-ledger checkpoint | Durable `(observationId, artifact)` commit marker returns the prior result without renewing again |
| Recovery | CSV replacement or projection acknowledgement fails | Pending projection generation survives and is retried at startup/backstop |
| Scalability | 100,000 rows share one deadline | Indexed keyset batches and a fixed cycle `asOf`; no per-row timers or full JVM materialization |
| Availability | An in-process event is lost | Nearest-deadline/projection state is durable and periodic reconcile still converges |
| Compatibility | A 0.2.0 DB starts the TTL-capable binary | `disabled` preserves behavior; activation is a separate named operation |
| Operability | Clock moves materially backwards | Readiness and stateful work fail closed with aggregate diagnostics |
| Privacy | Operator requests health | Only counts, states, ages and deadlines are exposed; no IOC, row key or source name |
| Extensibility | A future policy varies TTL by risk/type | The policy strategy changes; storage still receives an absolute deadline and lifecycle facts |

## 6. Target component model

```text
driving adapters / bootstrap
  WriteArtifactsStage        IngestionService       Lifecycle scheduler
           |                        |                         |
           +---------- application use cases ----------------+
                                      |
        +-----------------------------+-----------------------------+
        | ConfirmCanonicalRecords     | ReconcileExpiredRecords     |
        | ConvergeArtifactProjection  | PrepareLifecycleAdmission   |
        +-----------------------------+-----------------------------+
          |              |              |              |
          v              v              v              v
   CanonicalWriter  ActiveReader  ExpirationStore  LifecycleControl
          |              |              |              |
          +--------------+------ JDBC adapter ---------+
                                  |
                         canonical dataframe SQLite

   ConvergeArtifactProjection -> ArtifactProjection -> atomic CSV replace
   post-commit events ----------> local scheduler nudges only
```

### 6.1 Application capability

Add `com.iocextractor.application.artifact.lifecycle` in
`core/ioc-application`. It owns:

- `ExpirationPolicy` and V1 `FixedExpirationPolicy`;
- value objects such as `ObservationId`, `LifecycleId`, `EffectiveTime`,
  `LifecycleDeadline`, `LifecycleCloseReason` and projection generation;
- confirmation, expiry reconciliation, activation, projection convergence and
  aggregate-status use cases;
- storage-neutral commands/results and control-event facts.

This package is not moved into `ioc-domain`: the lifecycle belongs to configured
canonical artifact records, not to extraction taxonomy or the semantic identity
of an IOC. It must remain free of Spring, JDBC, CSV and Actuator.

### 6.2 Ports

Prefer client-shaped interfaces over growing the existing repository into a
single lifecycle god-interface:

| Port | Client and responsibility |
|---|---|
| `CanonicalArtifactWriter` | Atomically apply one artifact's prepared observations, provenance, idempotency marker, lifecycle, revision and projection-work facts |
| `ActiveArtifactReader` | Return an active-only snapshot for one artifact and one explicit `asOf`, including the observed projection generation |
| `ExpiredArtifactStore` | Read nearest deadline and atomically archive/delete one due keyset batch for a fixed cycle `asOf` |
| `LifecycleControlStore` | Persist activation, clock high-water, cycle/progress and aggregate status |
| `ArtifactProjectionWorkStore` | Read pending generations and compare-and-set acknowledgement after file installation |
| `ConfirmationReceiptStore` | Stage/complete/load/expire fingerprinted prepared-row receipts |
| `LifecycleStatusReader` | Read aggregate health facts without mutating clock or lifecycle state |

Atomic behavior remains coarse where it must: `CanonicalArtifactWriter` is a
semantic transaction port, not a CRUD repository. Its JDBC implementation may
use more than one physical transaction to reserve never-reusable IDs and then
commit the business transaction; that detail is not exposed to application
callers.

`CanonicalArtifactRepository` should be retired or narrowed into the writer and
active-reader ports. Adding lifecycle in a second call after the old `write()`
is forbidden because a crash would create an immortal or unconfirmed row.

### 6.3 Projection boundary

The existing `ArtifactProjection` can remain the sink boundary, but application
code must own durable convergence:

1. read pending `(requiredGeneration, projectedGeneration)`;
2. obtain one active snapshot with an explicit `asOf` and the generation it
   represents;
3. atomically replace the CSV;
4. acknowledge only the generation actually written;
5. if a newer generation appeared concurrently, leave work pending.

The projection result therefore needs to identify the observed generation. An
alternative later refactor may split snapshot reading from a pure CSV writer,
but that is not required to preserve the adapter boundary in V1.

## 7. Persistence design

### 7.1 Database ownership

Canonical lifecycle facts belong in the dataframe SQLite DB because they must
commit with the business rows they govern. The same DB also owns lifecycle
history, ID allocators, projection generations, observation commit markers and
prepared-row receipts.

The service DB continues to own source/ingest/export/fetch/publish coordination.
The ingestion ledger gains a stable delivery/observation attempt identity and
passes it into canonical writes, but it does not become the TTL source of truth.

This division deliberately avoids a distributed transaction. A missing complete
receipt only disables an optimization and causes ETL fallback; it cannot make an
inactive record active. A service-ledger checkpoint can be recovered from the
canonical observation marker.

### 7.2 Static dataframe-format tables

A versioned format migration should create stable, artifact-independent tables
equivalent to:

```text
canonical_lifecycle_control
  singleton_id, state, mode, activated_at_ms,
  safe_time_high_water_ms, clamp_started_at_ms, policy_fingerprint

lifecycle_id_allocator
  singleton_id, next_value, updated_at_ms

artifact_id_allocator
  artifact, strategy, next_value, identity_epoch, updated_at_ms

artifact_projection_state
  artifact, required_generation, projected_generation,
  requested_at_ms, projected_at_ms, last_error_code

canonical_observation
  observation_id, source_key, state, started_at_ms, terminal_at_ms

canonical_observation_commit
  observation_id, artifact, committed_at_ms, effective_as_of_ms,
  inserted, renewed, restarted, projection_generation

lifecycle_reconcile_cycle
  cycle_id, cycle_as_of_ms, state, started_at_ms, completed_at_ms,
  expired_count, affected_artifact_count, failure_code

confirmation_receipt
  receipt_id, source_key, processing_policy_fingerprint,
  state, expected_artifacts, row_count, completed_at_ms, expires_at_ms
```

Names are provisional, but the ownership and invariants are not. Every technical
instant used in range predicates is stored as UTC epoch milliseconds. Application
values remain `Instant`; conversion is isolated in the JDBC adapter.

Per-artifact commit markers belong to an observation header. Markers for a
non-terminal observation are never age-reaped: an offline daemon may recover the
same claimed delivery much later. After the ingest attempt is durably terminal,
the header can be acknowledged terminal in the dataframe DB and removed after a
bounded audit/idempotency retention. A crash before that acknowledgement causes
a safe leak, not a repeated confirmation; startup reconciliation can finish the
acknowledgement from service-ledger state.

### 7.3 Dynamic per-artifact schema

`DataframeSchemaReconciler` should add internal columns to every configured
active artifact table:

```text
_lifecycle_id                 INTEGER
_first_confirmed_at_epoch_ms  INTEGER
_last_confirmed_at_epoch_ms   INTEGER
_expires_at_epoch_ms          INTEGER
```

and create:

```text
UNIQUE(_lifecycle_id)
INDEX(_expires_at_epoch_ms, _lifecycle_id)
```

The physical columns must initially be nullable because SQLite cannot safely add
non-null columns to populated 0.2.0 tables without a rebuild. Null is permitted
only while the persisted lifecycle state is compatibility-disabled or
activating. `ACTIVE` publication is conditional on an invariant scan proving no
active row has partial lifecycle metadata.

Use `_lifecycle_id`, rather than public `id`, as the uniform keyset tiebreaker.
Some artifacts do not expose a public ID, public ID strategies are independent,
and lifecycle identity must remain stable across all artifact schemas.

For each artifact, create a typed `<artifact>_history` table that mirrors the
ordered public business columns and stores:

- a history primary key and the former storage/public identity;
- `row_key` and `_lifecycle_id`;
- first/last confirmation and expiration instants;
- `closed_at` and a stable close reason;
- the business-row snapshot as it existed in that lifecycle.

`<artifact>_history_sources` stores compact per-source first/last observation
and occurrence counts. An index on `(closed_at_epoch_ms, history_id)` supports
bounded retention. History never participates in active row matching.

For no-ETL replay, create typed `<artifact>_receipt_rows` tables containing
prepared business-row templates **without service-owned IDs**, plus receipt,
ordinal, row key and provenance fields. Typed tables avoid opaque JSON, a new
codec dependency and EAV row explosion. A receipt becomes readable only after
all expected artifact templates are staged and its header is marked complete.

### 7.4 Stable ID allocation

The process-local `ArtifactIdSequence` and active `MAX(id)+1` baseline cannot be
the authority once rows are deleted. Replace them with durable reservation:

1. the application passes prepared rows with deferred service-ID slots;
2. the JDBC writer first checks whether `(observationId, artifact)` was already
   committed;
3. if not, it durably reserves worst-case public and lifecycle ranges;
4. the canonical transaction materializes only the IDs it needs;
5. unused or failed reserved values remain gaps and are never returned.

Allocator state records direction and identity epoch; incompatible strategy
drift fails startup. Upgrade seeding must account for every existing public ID
and the configured direction/start, not only `MAX(id)` after activation.

Source-provided IDs remain optional namespaced provenance. A future
`(sourceNamespace, sourceRecordId)` relation can be added without changing the
service/lifecycle allocators or existing public output contracts.

## 8. Core flows

### 8.1 New source through ETL

```text
claim source and persist observationId
  -> parse/refang/extract/map
  -> failure-policy checkpoint
  -> for each artifact: canonical confirmation transaction
       already-applied observation? return stored result
       otherwise active row? renew
       otherwise due row? archive/delete + create new lifecycle
       otherwise insert new lifecycle
       update provenance
       increment artifact_revision only if at least one public row is new
       increment projection generation only if public membership changed
       stage typed receipt rows
       persist observation commit marker
  -> mark receipt COMPLETE after all expected artifacts committed
  -> converge dirty mutable projections
  -> close ingest-run and archive source
  -> mark canonical observation terminal (retryable cleanup acknowledgement)
  -> publish only post-commit latency hints
```

One artifact commit is the freshness confirmation boundary, matching the current
table-per-artifact transaction model. A complete source receipt is published
only after every expected artifact commit succeeds. Partial staging is ignored
and later reaped.

### 8.2 Repeated identical source

Content identity (`sourceKey`) and delivery identity (`observationId`) must be
separate. Every genuinely delivered file is a new observation even when bytes
match a terminal historical ingest.

```text
new observationId for delivered content
  -> complete unexpired receipt + matching policy fingerprint?
       yes: replay prepared templates through the same confirmation use case
       no:  run ordinary ETL and checkpoint
  -> normal projection/run-ledger/archive completion
```

The ingestion ledger must therefore stop using a terminal `source_key` as an
indefinite suppression decision. It should track an occurrence/attempt identity
that survives recovery. The same `observationId` is reused when a claimed file is
recovered, allowing the dataframe DB idempotency marker to close the current
commit-to-ledger crash window.

### 8.3 Confirmation versus expiration race

Both operations serialize through SQLite write ownership and use a half-open
deadline:

- confirmation owns the writer first and sees `expiresAt > asOf`: it renews;
- expiration owns the writer first and closes the due lifecycle: later
  confirmation inserts a new lifecycle;
- confirmation sees `expiresAt <= asOf`: it performs close-and-new itself;
- rollback changes neither lifecycle nor confirmation marker.

No in-memory lock is the correctness mechanism. A keyed guard may reduce local
contention, but the database transaction is authoritative.

### 8.4 Deadline reconciliation

One reconciliation cycle captures one safe `cycleAsOf`. For each configured
artifact it repeatedly selects a bounded keyset batch through
`(_expires_at_epoch_ms, _lifecycle_id)`, copies row/source snapshots to history,
deletes active rows and records projection work in the same transaction.

The first committed affected batch is enough to nudge projection: the projection
read predicate excludes **all** rows due at its own `asOf`, including rows not yet
physically archived. This lets the mutable CSV become correct without waiting for
the full 100,000-row history drain. Physical cleanup then continues in bounded
transactions and yields between batches to avoid writer starvation.

Expiry and legacy activation do not advance `artifact_revision`. They advance
only the separate mutable-projection generation/cycle state.

### 8.5 Active reads

Every read capable of feeding a current dataframe or export must receive one
explicit `asOf` and apply:

```sql
WHERE _expires_at_epoch_ms > :as_of_epoch_ms
```

In compatibility-disabled mode, legacy rows retain current read behavior. In
persisted active mode, a null lifecycle field is an invariant failure, not an
immortal row.

For immutable export, one `asOf` is captured for the full existing SQLite read
transaction and reused by metadata/coverage and every artifact row SELECT.
Coverage `upper_id` must be computed over the same active predicate. The
insert-driven revision pre-gate remains unchanged; therefore an explicit export
after expiry may still skip until a new insert or plan drift, as accepted in
I-20.

### 8.6 Projection convergence

Membership-changing canonical transactions perform:

```text
requiredGeneration := requiredGeneration + 1
```

Renewal and provenance-only updates do not. Projection writes a snapshot tagged
with generation `G`, atomically replaces the CSV, then acknowledges `G` by CAS.
If required generation is now greater than `G`, another pass remains pending.

This state is separate from both `artifact_revision` and `ingest_run`. Existing
ingest-run recovery should call the common projection convergence use case rather
than form a second projection truth. Redundant projection is safe; a false
acknowledgement of a newer generation is not.

## 9. Event-driven integration

### 9.1 Where events provide value

Use the existing `platform-events` publish-only contract for small aggregate
facts emitted **after** durable commit:

| Event fact | Consumer | Benefit |
|---|---|---|
| `CanonicalDeadlineScheduleChanged` | Lifecycle scheduler | Re-query/reschedule the nearest deadline after insert, renewal or shorter prospective policy |
| `MutableArtifactProjectionRequired` | Projection converger | Reduce stale-file latency after insert, expiry or activation |
| `CanonicalPublicRowsInserted` | Existing export nudge path | Express the accepted new-data-only trigger precisely |

Events carry observation/cycle identity, affected artifact names, generation or
nearest-deadline hints—not IOC values or one event per row. Listeners coalesce
work and re-read durable state.

The current `CanonicalArtifactsChanged` event is published after every completed
ingest even if zero rows were inserted. It should be narrowed or superseded by a
fact that is emitted only for actual new public rows. Reappearance after expiry
qualifies; renewal and expiry do not.

### 9.2 What events must not own

- active/inactive truth;
- physical expiry or history completion;
- projection acknowledgement;
- immutable export creation after expiry;
- clock high-water;
- retry counts or durable delivery.

No transactional outbox is needed in V1 because losing any proposed event only
loses latency. Indexed deadline discovery, projection generations and periodic
reconciliation recover the work. If a future external consumer requires durable
delivery, that is the point to introduce an outbox/broker adapter—not before.

Event sourcing and CQRS are not justified: the service needs current canonical
truth plus bounded audit history, not an unbounded event log or two independently
consistent business models.

## 10. Startup and runtime admission

### 10.1 Required ordering

The existing ingestion barrier is necessary but not sufficient because export
and other `SmartLifecycle` schedulers can start before `ApplicationRunner`.
Introduce one explicit canonical-data admission graph:

```text
dataframe format migration
  -> per-artifact schema reconciliation
  -> identity/allocator validation
  -> existing ingest-run recovery
  -> lifecycle config + clock validation
  -> resume/finish legacy activation
  -> reconcile rows already due
  -> converge pending mutable projections
  -> mark canonical data admitted
  -> start export formation, lifecycle scheduler and ingestion intake
```

This should be composed in bootstrap from small application recovery steps. It
must not be several unrelated runners whose order is implicit. Stateful use
cases also check the admission state defensively so oneshot `extract` and
`export` cannot bypass it. Help and read-only health do not perform mutation.

Publish/fetch behavior remains outside TTL unless it forms a new export or opens
ingestion. An already completed immutable slice may still be published because
I-20 explicitly permits it to remain stale after later canonical expiry.

### 10.2 Activation state machine

Recommended persisted states:

```text
DISABLED_COMPATIBLE -> ACTIVATING -> ACTIVE
```

- fresh fixed installations with no legacy rows enter `ACTIVE` during first
  admission;
- old databases first run in `DISABLED_COMPATIBLE` unchanged;
- `existing-records: expire` records `ACTIVATING`, archives/deletes legacy rows
  in resumable per-artifact batches and requests mutable projection;
- only invariant validation and projection convergence publish `ACTIVE`;
- `ACTIVE -> disabled` is rejected for the same DB;
- activation does not auto-replay archived source documents and may produce an
  empty active dataframe;
- activation does not advance insert-driven export revision or create a slice.

### 10.3 Clock policy

Use the injected UTC wall clock for absolute deadlines and monotonic elapsed time
for waits/retries. A durable safe-time high-water prevents an observed backward
step from making records active longer without notice:

- raw time at or above high-water: accept and advance high-water;
- a small bounded backward step: use the high-water as effective time and report
  `DEGRADED`;
- excessive skew or a clamp lasting beyond the configured safety window: report
  `DOWN`, close readiness and reject stateful work;
- a forward jump is accepted and may create a large due backlog.

Health reads the latest clock state but never advances it. Exact skew and clamp
tolerances must be finalized and fault-tested before P4; they are not business
TTL values.

## 11. Configuration contract

Recommended strict configuration shape:

```yaml
ioc:
  lifecycle:
    expiration:
      mode: disabled            # disabled | fixed
      fixed-ttl: 12h            # required and > 0 when fixed
      existing-records: reject  # reject | expire; meaningful only at activation
      history-retention: 30d
      receipt-retention: 30d
      reconcile:
        backstop-interval: 5s
        batch-size: 1000
      clock:
        max-backward-skew: 2s
        max-clamp-duration: 30s
```

The two clock defaults and batch size are architecture recommendations, not yet
accepted product constants; benchmarks and failure tests may adjust them. The
five-second start target, `12h` fresh-install TTL and `30d` retention defaults
are accepted requirements.

Classpath/upgrade configuration remains `disabled`; the fresh production
packaging template explicitly selects `fixed/12h`. Zero and negative durations
are invalid. `ttl=0` is never a data-deletion command.

`processingPolicyFingerprint` must cover every setting/version that can alter a
prepared canonical row: parser/source decoding, refang, patterns, extraction,
classification, attribution, deduplication, artifact routing/mapping,
normalization, identity epoch and failure policy. Receipt replay fails closed to
ordinary ETL when the fingerprint differs or cannot be proven.

## 12. Scale and SQL strategy

The expected tens of thousands of active rows, with a 100,000 same-deadline
validation envelope, remains a good fit for one SQLite dataframe DB.

Required controls:

- numeric composite due indexes and `EXPLAIN QUERY PLAN` assertions;
- prepared/batched SQL and keyset iteration, never `OFFSET` pagination;
- one fixed `cycleAsOf` per drain pass;
- bounded write transactions with an explicit yield between batches;
- no collection of the full due set in Java;
- one projection per affected artifact/generation, not per expired row/batch;
- WAL/read snapshot behavior retained so active readers can proceed during
  bounded writes;
- retention cleanup independent from active expiry;
- non-terminal observation idempotency markers excluded from age-based cleanup;
- aggregate count queries designed against indexes, not per-row health scans;
- benchmark records for start latency, batch duration, total drain, maximum
  transaction time, writer contention, memory and projection latency.

The release gate is not merely “100k eventually completed.” It must show that
logical exclusion is exact, reconciliation starts within five seconds in a
healthy idle daemon, intake is not starved, and the mutable projection converges
within a measured and documented bound.

Partitioning, a second database engine or distributed scheduling should be
reconsidered only if measured active/history volume, write contention or
multi-process deployment exceeds this envelope.

## 13. Maven module and Java library decision

### 13.1 V1 decision: keep existing modules

| Concern | Placement | Reason |
|---|---|---|
| Lifecycle language/policy/use cases | `core/ioc-application` package | Depends on canonical artifact concepts and has one application consumer |
| Port contract tests | `core/ioc-application-tck` | Existing reusable adapter-contract location |
| SQLite implementation | `adapter-store-jdbc` | Same JDBC/SQLite integration family; no new technology boundary |
| CSV convergence | `adapter-sink-csv` | Existing CSV/filesystem integration family |
| Duplicate intake path | `adapter-ingest` + application ingest use case | Existing driving adapter and orchestration |
| Scheduling/config/health | `bootstrap/ioc-app` | Spring runtime boundary and composition root |
| Generic keyed/event primitives | existing `platform-*` | Already reusable; no TTL knowledge should enter them |

A new `ioc-lifecycle` module now would either depend on application artifact
types, making it a thin forwarding layer, or force storage-specific canonical
concepts into a falsely generic API. Both increase dependency and release
surface without a second consumer.

No new third-party Java library is needed. JDK time/concurrency, existing Spring
Boot scheduling/bootstrap and SQLite are sufficient. Typed receipt tables avoid
introducing a serialization library solely for an internal cache.

### 13.2 Future extraction criteria

Extract a separate `core/ioc-lifecycle` library only when all are true:

1. at least two real application capabilities or executables consume it;
2. its public model no longer depends on IOC artifact row classes;
3. policy and state-machine API has stabilized through V1 operational evidence;
4. it can depend only inward and has no Spring/JDBC/CSV concepts;
5. independent versioning/testing reduces, rather than duplicates, change cost.

A second persistence technology does not itself require a new core module; it
requires another adapter behind the same ports. An external durable event
consumer would justify an outbox/broker adapter, not moving TTL into
`platform-events`.

## 14. Operational and security model

Health exposes aggregate fields such as:

- configured/effective mode and persisted activation state;
- active/due/history counts by artifact;
- nearest deadline, last cycle and due backlog age;
- required/projected generation lag;
- receipt count/retention lag;
- clock state/skew/clamp age;
- last failure code and admission/readiness state.

It must not expose IOC values, row keys, source names, source IDs or raw receipt
templates. Logs/events follow the same rule and use bounded aggregate counts.

History and receipts extend the period for which IOC/source-derived data exists
on disk. They inherit dataframe DB permissions, backup encryption/handling and
secure deletion expectations. Retention is a release requirement, not optional
housekeeping. Dynamic table/index identifiers continue through the adapter's
strict SQL identifier validation; data stays parameterized.

## 15. Risk register

| Risk | Severity | Control and required evidence |
|---|---|---|
| Due row leaks before delete | Critical | Active predicate on every read; delayed-cleanup tests |
| Public ID reused after deletion/restart | Critical | Durable direction-aware allocator; highest-ID expiry/restart tests |
| Crash retry extends TTL twice | High | Durable observation ID and per-artifact commit marker; fault injection at commit/ledger boundary |
| Terminal source ledger suppresses future confirmation | High | Separate content and observation identity; receipt or ETL path on every new delivery |
| Expiry accidentally creates immutable export | High | Separate revision/generation stores and event types; I-20 trigger tests |
| Mutable CSV remains stale | High | Durable projection generation, CAS ack and startup/backstop convergence |
| Clock rollback prolongs activity | High | Durable high-water, clamp window, fail-closed readiness tests |
| Confirmation/expiry race loses observation | High | Real SQLite concurrency tests with controlled latches and transaction-order assertions |
| Activation partially exposes legacy data | Critical | Persisted resumable state and common admission barrier; kill/restart matrix |
| 100k expiry monopolizes writer | High | Batch/yield benchmark, transaction-duration threshold and intake starvation test |
| Typed history drifts from configured schema | Medium | Dynamic reconciler, schema fingerprint and additive/destructive drift tests |
| Receipt cache grows without bound | Medium | Complete-only reads, 30-day retention, partial cleanup and size metrics |
| Lost/duplicate event affects truth | Low by design | Events only nudge idempotent reconcilers; periodic durable backstop |
| Two DBs restored inconsistently | High | Coordinated backup/config contract and fail-closed identity/policy validation |
| `12h` creates gaps between daily feeds | Accepted business trade-off | Operator documentation and active-count health; no hidden grace period |
| Internal timestamps leak into public schema | High compatibility | Golden byte/order tests; public `time_*` remain `NULL` |

## 16. Implementation decomposition

The existing P0–P6 sequence remains appropriate with these refinements:

1. **P0 — characterization and design acceptance**
   - accept this architecture before accepting ADR-0020;
   - characterize every active read and export pre-gate;
   - characterize partial artifact commit/recovery and source duplicate paths;
   - fix exact config/clock/performance parameters.
2. **P1 — application contracts/TCK**
   - lifecycle values, fixed policy, observation idempotency, client-shaped
     ports and TCK; no runtime activation.
3. **P2 — dormant persistence foundation**
   - static format migration, dynamic lifecycle/history/receipt schema,
     durable allocators, observation markers and projection generations;
   - disabled behavior remains compatible.
4. **P3 — lifecycle-aware write/read**
   - atomic confirmation variants, one effective `asOf`, active predicates,
     durable ID reservation and projection CAS;
   - preserve insert-only export revision.
5. **P4 — expiry/runtime safety**
   - bounded reconciliation/retention, projection convergence, event nudges,
     clock policy, common admission and aggregate health.
6. **P5 — ingestion and activation UX**
   - observation-aware ledger/recovery, fingerprinted receipts, ETL fallback,
     resumable legacy expiry and both runtime modes.
7. **P6 — release closure**
   - fresh package `fixed/12h`, two-step upgrade/rollback docs, generated
     catalogs, 100k evidence and fresh full-reactor verification.

No intermediate slice may enable fixed TTL in the production template. Dormant
schema is acceptable; a partially admitted lifecycle is not.

## 17. Mandatory verification model

### 17.1 Deterministic unit/TCK

- fixed deadline calculation and positive-duration validation;
- half-open boundary and prospective duration change;
- already-applied observation idempotency;
- insert/renew/due-recreate result classification;
- independent revision and projection-generation rules;
- clock clamp/down state transitions;
- projection generation CAS.

### 17.2 Real SQLite integration

- migration from a 0.2.0 fixture and fresh schema;
- ID reservation, gaps and restart after maximum-row expiry;
- active predicate in repository load and immutable multi-artifact snapshot;
- confirmation/expiry concurrency in both transaction orders;
- crash points around history copy, delete, observation marker, projection
  replace/ack and activation progress;
- incomplete/mismatched receipt fallback;
- query-plan assertions for due/retention/status paths.

### 17.3 Runtime/package evidence

- daemon recovery before lifecycle scheduler/export/intake;
- stateful oneshot extraction/export admission;
- disabled compatibility start and fixed fresh install;
- destructive two-step activation, interruption/resume and coordinated restore;
- clock backward/forward simulations without wall-clock sleeps;
- 100k same-deadline reference run with stored environment and thresholds;
- public CSV/export golden bytes, order and null `time_*` contract;
- expiry-only no-slice and next-new-row active-only slice scenarios.

## 18. Decisions, recommendations and remaining parameters

### 18.1 Architecture decisions ready for review

- lifecycle ownership is canonical artifact record/application capability;
- active predicate is authoritative; cleanup is reconciliation;
- existing modules and integration families are sufficient;
- durable allocator and observation idempotency are mandatory, not optional
  optimizations;
- mutable projection generation is distinct from immutable export revision;
- events are lossy aggregate nudges over durable work;
- typed per-artifact history and receipts avoid a generic JSON/EAV subsystem;
- one explicit admission graph replaces implicit lifecycle/runner ordering.

### 18.2 Parameters to finalize before implementation

- exact config property names after strict-preflight compatibility review;
- default reconciliation batch size after SQLite benchmark;
- maximum backward clock skew and maximum clamp duration;
- precise 100k reference host/storage profile and regression thresholds;
- ingestion-ledger schema migration shape for stable observation attempts;
- whether the current projection port is minimally extended or split into
  snapshot-reader and row-writer ports during P3.

These parameters do not reopen the accepted business semantics. They select safe
implementation values and the least disruptive API migration after executable
characterization.

## 19. Rejected alternatives

- source- or IOC-owned TTL;
- a central polymorphic lifecycle table joined by every read;
- soft-delete as the active model;
- cleanup timing as the active/inactive boundary;
- `ttl=0` as a migration command;
- recalculation of stored deadlines after config edits;
- active `MAX(id)+1` as permanent ID authority;
- per-row Java timers;
- expiry-driven `artifact_revision` or immutable export;
- in-memory events as work authority;
- an outbox/broker/event-sourced model in V1;
- a new Maven module or generic TTL library without a second consumer;
- opaque serialized receipts when the existing typed artifact schema can be
  reused.

## 20. Review gate

ADR-0020 may move from draft to proposed/accepted only after review confirms:

1. this component/data/transaction model;
2. the separate revision versus projection-generation semantics;
3. observation identity and receipt fallback behavior;
4. the common startup admission graph;
5. the no-new-module decision and future extraction criteria;
6. the remaining implementation parameters or the P0 evidence used to fix them.

Production implementation still requires a separate explicit go-ahead after
that review.
