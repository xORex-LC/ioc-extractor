# 0023 — Bounded lifecycle reconciliation runtime state

## Status

**Accepted on 2026-08-23. Implemented in the 0.3.0 candidate; final release
qualification remains pending.**

This ADR narrowly supersedes ADR-0020 where that decision models every periodic
expiry check as an append-only `lifecycle_reconcile_cycle` row and couples
history retention to the deadline worker. ADR-0020 remains authoritative for
record validity, expiration semantics, recovery, active reads, projections,
history content, and all other lifecycle behavior.

## Context

The daemon uses lossy in-process events to reduce latency and a five-second
periodic backstop to rediscover durable lifecycle work. The first implementation
ran a complete reconciliation cycle at every backstop tick. Even when no record
was due, it:

- inserted and completed a durable cycle row;
- emitted a successful reconciliation INFO event;
- ran independent history retention; and
- left an unbounded operational journal behind.

The projection backstop independently emitted another INFO event for an empty
pass. With a five-second interval, an idle instance accumulated about 17,280
cycle rows and 34,560 uninformative INFO events per day. Increasing the interval
would hide the symptom by weakening the accepted expiry-latency contract.

## Decision

### 1. Keep events as hints and make the backstop deadline-aware

`CanonicalDeadlineScheduleChanged` remains a latency hint. The durable
`MIN(_valid_until_epoch_ms)` query remains the scheduling authority.

Both an event nudge and the five-second backstop refresh the one aggregate
nearest-deadline timer. They do not start reconciliation when no deadline is
due. A due timer starts the existing bounded, idempotent reconciliation use
case. Restart and a lost event are therefore still recovered within the
configured backstop interval without a timer or job per IOC.

### 2. Store only the latest reconciliation checkpoint

Dataframe format v6 adds one `lifecycle_reconcile_state` singleton row. It keeps
a monotonic `cycle_sequence`, current/last terminal state, timestamps, aggregate
counters, and failure code. Starting a real due cycle increments the sequence;
batch accounting and terminal publication update the same row.

The v6 migration seeds the singleton from the latest legacy
`lifecycle_reconcile_cycle` row, or from `NEVER_RUN` when no cycle exists. The
legacy table is retained read-only and receives no new rows. This preserves
upgrade evidence and rollback inspection while bounding all post-upgrade state.
Health reads the singleton and remains read-only.

### 3. Separate history retention from deadline scheduling

History retention gets its own admission-gated `SmartLifecycle` worker and
configuration cadence, `ioc.lifecycle.history-cleanup-interval` (default one
hour). Each transaction remains bounded by the existing reconciliation batch
size. When a pass reports more eligible rows, the worker schedules another task
immediately on its owned single-thread executor until the backlog is drained.

Expiration and history deletion consequently have independent timing and
failure domains. The business retention duration remains
`ioc.lifecycle.history-retention` (default 30 days).

### 4. Log outcomes, not idle heartbeats

A successful reconciliation is logged at INFO only when it expired records. A
projection pass is logged at INFO only when it projected an artifact or still
has pending work. Empty successful checks are silent; every failure remains a
diagnostic plus ERROR. History cleanup already logs only a non-zero purge.

### 5. Preserve existing boundaries

No broker, outbox, Spring Batch job, per-record scheduler, new Maven module, or
standalone Java library is introduced. Framework-free use cases and ports stay
in `ioc-application`; SQLite details stay in `adapter-store-jdbc`; scheduling
and Spring lifecycle ownership stay in `ioc-app`.

## Consequences

Positive consequences:

- the five-second correctness bound is preserved without idle SQLite writes;
- reconciliation runtime state has constant cardinality after v6;
- idle lifecycle logs no longer drown material transitions and failures;
- retention cadence can change without changing expiry latency;
- event loss and restart remain recoverable from durable data.

Costs and limits:

- the legacy journal is frozen rather than deleted, so existing rows continue
  to occupy their pre-upgrade space;
- only the latest post-v6 cycle checkpoint is retained; long-term operational
  trends belong in external logs/metrics, not canonical SQLite;
- the periodic projection backstop still performs small durable reads every
  five seconds because it is the correctness path for a lost projection hint.

## Rejected alternatives

- **Increase the backstop interval:** reduces writes but weakens expiry latency.
- **Keep append-only cycles and periodically delete them:** creates avoidable
  writes, cleanup work, and a second retention policy for heartbeat data.
- **Use one scheduler for expiry and history retention:** couples unrelated
  cadences and failure domains.
- **Run reconciliation only from events:** a process crash can lose the hint.
- **Create one scheduled job per record:** makes scheduler state proportional
  to IOC cardinality and duplicates the persisted deadline authority.
- **Adopt Spring Batch or ShedLock now:** the current single-node, bounded
  SQLite workload does not justify their runtime and schema complexity.

## Verification

- repeated empty backstop refreshes do not invoke reconciliation or advance the
  durable cycle sequence;
- a due deadline invokes exactly one non-overlapping reconciliation and then
  refreshes the next durable deadline;
- v5-to-v6 migration preserves the latest legacy cycle and freezes the old
  table;
- interrupted `STARTED` state is marked `FAILED` and the next real cycle
  completes with a higher sequence;
- retention scheduling is admission-gated, independently configured, and
  drains `moreEligible` work through bounded follow-up tasks;
- no-op reconciliation/projection outcomes emit no INFO while material and
  failed outcomes retain structured events;
- config binding, strict validation, health, focused module tests, docs, and
  the full repository gate pass on the resulting commit.
