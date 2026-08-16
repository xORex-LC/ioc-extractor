# 0020 — Canonical record validity and expiration lifecycle

## Status

**Accepted on 2026-08-16. Implementation in progress; P1 application contracts
are complete, while storage and runtime behavior remain inactive.**

This ADR establishes a canonical-record validity and expiration capability for
release 0.3.0. Until its storage, pipeline and runtime slices are implemented
and activated, canonical records retain the behavior described by the existing
storage and export documentation.

## Context

SQLite is the source of truth for canonical artifact rows. Mutable dataframe
CSV files and immutable export slices are projections. The current keep-first
model retains a row indefinitely, updates provenance on repeated observations,
and derives service-owned public IDs from active storage state.

That model cannot express freshness. An IOC that has not been confirmed for a
business-defined period continues to appear in new projections and export
slices. Adding only a periodic `DELETE` would be unsafe because expiration also
affects canonical read semantics, public-ID allocation, revisions, projection
recovery, ingestion duplicates, startup admission, upgrade and rollback.

The requirements are:

- any accepted observation may confirm a canonical record, while absence from a
  later incomplete feed is not a revocation;
- expiration is exact at the read boundary and survives process downtime;
- an observation after expiration starts a new lifecycle rather than reviving
  historical state;
- existing public artifact schemas remain compatible;
- upgrade from records without lifecycle metadata is explicit, recoverable and
  operator-controlled;
- the implementation remains within the existing clean hexagonal boundaries
  and SQLite operational envelope.

## Decision

### 1. Validity belongs to the canonical record lifecycle

TTL belongs to a storage-neutral canonical artifact record lifecycle, not to an
IOC type, source or provenance row. A source supplies observation evidence;
application policy calculates a deadline; the storage adapter commits the
record, provenance and lifecycle atomically.

V1 provides one `FixedRecordValidityPolicy` behind the small
application-level `RecordValidityPolicy` strategy. The strategy returns an
absolute validity decision rather than exposing storage or scheduling details.
Per-source, per-type and rules-engine policies are deferred. Fixed TTL must be
strictly positive.

An observation confirms freshness only when its canonical transaction commits
successfully after parsing and the failure-policy checkpoint. File detection,
parsing start and a rolled-back transaction do not confirm a record. Each
transaction uses one effective UTC `asOf`, obtained after write ownership is
acquired.

### 2. Activity is an exact, half-open interval

An active lifecycle stores internal UTC instants equivalent to:

- `first_confirmed_at`;
- `last_confirmed_at`;
- `valid_until`.

The active interval is `[first_confirmed_at, valid_until)`. Every canonical read
that can feed a dataframe or export applies `valid_until > asOf`; therefore a
row is inactive when `asOf == valid_until`, even if physical cleanup has not yet
run.
Reads do not recalculate stored deadlines from current configuration.

`valid_until` is internal canonical lifecycle vocabulary aligned with the STIX
2.1 meaning of an Indicator validity boundary. It does not make an artifact row
a STIX Indicator and does not map `first_confirmed_at` to STIX `valid_from`.
Source assertions and STIX projection remain separate versioned mapping
contracts.

When an accepted observation finds an active lifecycle, it renews the deadline
to `asOf + fixedTtl`. When it finds the same row already due, the old lifecycle
is closed and the observation creates a new lifecycle. A concurrent expiration
and confirmation is resolved by the canonical database transaction order; an
observation is never lost and history is never reactivated.

### 3. Active state and bounded history have separate roles

Expired rows leave active canonical storage. Reconciliation copies a typed,
ordered business-row snapshot, lifecycle facts, the former identities, a close
reason and a compact source summary to history before deleting active state.
History does not participate in row-key matching, renewal, projection, export or
ID allocation.

History retention is configurable and defaults to 30 days. V1 exposes aggregate
history and reconciliation statistics, not per-IOC history search. Retention
cleanup uses bounded batches and never changes active artifact revisions.

Each active artifact table carries its lifecycle fields and an index ordered by
deadline and stable row identity. The JDBC adapter owns matching typed history
tables and durable lifecycle control state. A central polymorphic lifecycle
table and opaque JSON archive are rejected because they weaken referential and
typed-schema guarantees and add joins to every active read.

### 4. Service-owned identity is monotonic across lifecycles

A service-owned public ID is never reused, including after expiration or
history retention. Each artifact uses durable allocator state independent of
`MAX(id)` over active rows; reserved but uncommitted ranges may create gaps.
Internal lifecycle identity uses a separate durable namespace.

Source-supplied IDs are opaque, namespaced provenance references. They do not
replace internal or service-owned public identity. Merged outputs keep the
service-owned ID; exact source IDs require an explicit source-scoped or
namespaced output contract. Reusing one source ID for another IOC is an identity
conflict by default, not a silent remap.

### 5. Expiration preserves insert-driven immutable export revisions

Observation-only renewal changes internal lifecycle and provenance facts but
does not change public row bytes, increment the artifact revision or trigger an
export solely to publish a new time. The existing `artifact_revision` remains
insert-driven: it advances only when a successful canonical commit adds at least
one new public active row. Expiration/removal, renewal, an identical duplicate
and a source with no new public rows do not advance it and do not request a new
immutable slice. Reappearance after expiration creates a new lifecycle/public
row and therefore does qualify as new data.

Consequently, the latest completed immutable slice may continue to contain rows
that have since expired, and no empty slice is automatically created when the
active set becomes empty. The next new-data-triggered export reads the current
active snapshot and excludes every accumulated expired row. Existing explicit
operator export, plan-drift and export-recovery behavior remain separate from
this automatic daemon trigger rule.

The existing public `time_first_seen` and `time_last_seen` columns retain their
position and remain `NULL` in V1. Internal lifecycle timestamps are not mapped
to those business fields. `valid_until` is not added to existing dataframe or
export schemas. A future consumer deadline is a separate versioned mapping or
integration contract.

Canonical expiry and derived-file convergence are not one ACID transaction.
The canonical transaction records durable mutable-projection work. Projection
or export failure cannot resurrect an expired row; startup and periodic
reconciliation bring the mutable dataframe back to canonical truth. Immutable
export is intentionally not regenerated solely because of expiration.

Lifecycle projection-work/cycle state is separate from `artifact_revision`.
It makes mutable dataframe convergence and crash recovery durable without
becoming another immutable-export trigger.

### 6. Reconciliation is deadline-aware, bounded and recoverable

Logical exclusion is exact at the deadline. Physical reconciliation selects due
rows through indexed keyset batches and bounded SQLite transactions, then
coalesces projection work once per affected artifact and cycle. It does not use
per-row timers, frequent full-table scans or full-set JVM materialization.

The daemon maintains one nearest-deadline wake-up and a periodic correctness
backstop. In a healthy idle daemon, reconciliation starts within five seconds of
the deadline. V1 release evidence covers 100,000 simultaneously due records as
a validation envelope, including bounded drain, absence of writer starvation
and eventual convergence. This is not a hard product-size limit.

The bootstrap implementation uses a `SmartLifecycle`-managed scheduler on an
explicitly owned `ScheduledExecutorService`. It remains inert until the common
canonical-data admission barrier opens, then reschedules from durable nearest-
deadline state after each aggregate hint or reconciliation pass.

### 7. Time is an isolated system dependency

Absolute expiry uses an injected system UTC `Clock`; timezone and daylight
saving changes do not affect stored instants. Monotonic elapsed time controls
scheduler waits, retry delays and durations, but cannot replace wall time across
restart.

Durable lifecycle time has a non-decreasing high-water mark. A small backward
clock correction is clamped and reported as degraded. A material or prolonged
rollback makes lifecycle state untrustworthy, reports down and closes readiness
and stateful work. Exact tolerances must be explicit configuration or documented
policy derived from implementation and operational evidence. A forward jump may
make any number of records immediately due and is handled by bounded
reconciliation.

### 8. Activation is explicit and one-way for an existing database

The classpath and upgrade-compatible default has record validity disabled. A
fresh production installation explicitly enables fixed TTL with a 12-hour
default.
This value intentionally permits an empty active set or gaps between feeds when
observations are less frequent than the TTL.

Existing installations use a two-step rollout:

1. deploy the validity-capable application and verify it with validity mode
   disabled;
2. stop stateful work, capture the exact configuration and a consistent backup
   of both SQLite databases, then explicitly enable the fixed record-validity
   policy with the named `existing-records: expire` activation policy.

Activation runs idempotently before readiness, intake and export. It closes all
legacy records with audit, activation-cycle and projection recovery, so active
output may legitimately become empty. It does not advance the insert-driven
artifact revision. Archived inputs are not replayed automatically.
The fixed duration value `0` is invalid and never acts as a destructive command.

After durable activation, starting the same database with validity disabled
fails closed. Duration changes are prospective: existing absolute deadlines
remain until the next accepted observation. V1 has no deactivation, retroactive
recalculation, bulk extension or manual expiry command.

Successful readiness is the ordinary rollback boundary. Before it, the operator
may restore the consistent pre-activation application, configuration and both
databases. After it, roll-forward and idempotent retry are primary; restoring the
old snapshot is disaster recovery that may lose later confirmations. Restoring
only one component is unsupported.

### 9. Duplicate-source confirmation is a bounded optimization

Source content identity and delivery/observation identity are separate. Every
newly delivered source receives a durable observation identity; recovery of the
same claimed delivery reuses it. The canonical transaction stores an
artifact-scoped observation commit marker, so a crash between canonical commit
and service-ledger checkpoint cannot renew TTL a second time.

An identical source can confirm prepared canonical observations without running
ETL only when a complete durable receipt exists and its processing-policy
fingerprint matches the current parsing, normalization, mapping, identity and
failure policies. Receipt retention uses the same configurable 30-day default as
lifecycle history.

After receipt retention or policy drift, the identical source follows the
ordinary ETL and checkpoint path. This preserves correctness while bounding
storage; no-ETL handling is not an indefinite guarantee.

### 10. Startup admission and health are separate responsibilities

All stateful entry points share an idempotent lifecycle admission sequence after
schema and existing recovery: validate policy and clock, resume activation,
reconcile due state, converge pending service-owned projections, then open
daemon intake or execute the stateful command. Health only reads this state and
does not own admission.

Recoverable lag is degraded and may keep intake open while the active-read
invariant remains provable. Failure to read or reconcile lifecycle control,
policy/config mismatch, unsafe clock rollback or loss of the active predicate is
down and fail-closed. Health and logs expose aggregate counts, deadlines,
backlog, cycle and history state without IOC values, row keys or source names.

The daemon export backstop continues to observe insert-driven
`artifact_revision`. Ingestion events remain latency hints; the lifecycle
subsystem publishes no export hint for expiration.

## Architecture placement

- `core/ioc-application` owns lifecycle semantics, policy, use cases and inward
  ports; reusable port contracts live in `ioc-application-tck`.
- `adapter-store-jdbc` owns SQLite migrations, atomic SQL, indexing, history,
  durable allocators and recovery state.
- `adapter-sink-csv` remains a projection adapter and does not own TTL policy or
  scheduling.
- `adapter-ingest` uses complete receipts or the ordinary ETL fallback.
- `bootstrap/ioc-app` owns typed configuration, composition, startup admission,
  deadline scheduling and read-only health.
- `core/ioc-domain` remains unchanged unless a separate domain need is proven.

No new runtime library or Maven module is required for V1.

## Consequences

- Freshness becomes part of canonical data quality rather than a downstream
  delivery concern.
- Every canonical read path must carry an explicit effective `asOf`; an omitted
  predicate becomes a correctness defect.
- Public identity allocation and mutable-projection recovery become durable even
  when active rows are deleted, without changing insert-driven export revision.
- Existing installations require an operator-visible destructive cutover and a
  coordinated rollback point.
- The 12-hour production default favors strict freshness over continuity between
  daily feeds.
- Immutable export intentionally has weaker freshness than canonical reads and
  mutable dataframe after expiry: without later new public data, the latest
  completed slice can remain stale indefinitely.
- SQLite remains appropriate for the expected tens-of-thousands workload, but
  100k same-deadline evidence and query-plan checks become release gates.
- Future per-source policy, public timestamps or target-specific deadlines can
  be added at their proper policy/mapping boundaries without changing V1
  lifecycle ownership.

## Rejected alternatives

### Soft-delete expired rows indefinitely in active tables

Rejected because historical rows would continue to compete in row-key and
public-ID semantics, grow every active read and make a later observation look
like reactivation rather than a new lifecycle.

### Use `ttl=0` to clear legacy data

Rejected because a declarative duration would hide a one-time destructive
operation and would also expire every later observation. Activation uses the
named, persisted and idempotent `existing-records: expire` policy.

### Make source/provenance own TTL

Rejected because one record can be confirmed by many sources and V1 does not
define source withdrawal. Expiry belongs to the canonical lifecycle; provenance
remains evidence.

### Recalculate all deadlines when configuration changes

Rejected because an ordinary config edit would silently become a bulk data
migration. Stored deadlines are facts and policy changes are prospective.

### Use per-row timers or a new scheduler/database

Rejected because timers are not durable and scale with record count, while the
existing SQLite model can provide indexed deadline discovery, bounded batches
and startup reconciliation without a new integration family.

Spring `@Scheduled` is not used because V1 needs dynamic nearest-deadline
rescheduling and explicit startup admission phases. ShedLock is not used because
the supported deployment has one daemon and correctness is already serialized
by SQLite transactions; a future multi-process deployment requires a broader
writer/fencing design. Spring Batch is not used because its job metadata and
restart/partition model would duplicate the bounded keyset reconciler and
durable lifecycle cycle state at the current validation envelope.

### Map ordinary time expiry to `revoked`, `detection` or decay score

Rejected because STIX revocation is permanent for an object identity, whereas
an expired IOC may later create a new lifecycle and identity. OpenCTI detection
and score are platform-specific state, and the existing public artifact `score`
has an independent contract. V1 records `LifecycleCloseReason.EXPIRED` and does
not calculate a decay score on every canonical read.

### Treat events or successful projection as expiration authority

Rejected because events can be lost and filesystem/remote side effects cannot
join the canonical SQLite transaction. Durable canonical state and reconcile
are the correctness authority.

## Required evidence before acceptance as implemented

- deterministic clock-based lifecycle and boundary tests;
- real-SQLite confirmation-versus-expiry race and rollback tests;
- legacy activation, fault-injection, restart and projection convergence tests;
- active-only contracts for canonical, mutable dataframe and immutable export
  reads;
- automatic-export trigger tests proving that expiry alone creates no slice and
  that the next new-data export removes accumulated expired membership;
- daemon and stateful oneshot/export configuration and admission tests;
- packaged fresh-install, two-step upgrade and coordinated rollback evidence;
- reproducible 100k same-deadline evidence, reference-environment profile and a
  measured drain regression threshold;
- updated capability, operator, configuration, observability and release docs;
- final clean reactor verification on the release candidate.

## Reference inputs

The local decision was compared with established lifecycle models and runtime
mechanisms. These references inform vocabulary and extension seams; they are not
adopted as wire formats or implementation dependencies:

- [STIX 2.1 specification](https://docs.oasis-open.org/cti/stix/v2.1/os/stix-v2.1-os.html)
  for Indicator `valid_until`, `valid_from` and permanent revocation semantics;
- [OpenCTI Indicators lifecycle](https://docs.opencti.io/latest/usage/indicators-lifecycle/),
  [decay rules](https://docs.opencti.io/latest/administration/decay-rules/),
  [retention policies](https://docs.opencti.io/latest/administration/retentions/)
  and its
  [expiration manager](https://github.com/OpenCTI-Platform/opencti/blob/master/opencti-platform/opencti-graphql/src/manager/expiredManager.js)
  for separation of validity, expiration management and retention;
- MISP
  [`DecayingModel`](https://github.com/MISP/MISP/blob/2.5/app/Model/DecayingModel.php)
  and the versioned
  [`misp-decaying-models`](https://github.com/MISP/misp-decaying-models)
  catalog for the future policy/strategy seam;
- [Spring scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html),
  [ShedLock](https://github.com/lukas-krecan/ShedLock) and
  [Spring Batch chunk processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)
  for the V1 runtime/framework disposition.

## Related published documents

- [Architecture](../ARCHITECTURE.md)
- [Storage](../dev/storage.md)
- [Processing](../dev/processing.md)
- [Artifact export](../dev/artifact-export.md)
- [Ingestion](../dev/ingestion.md)
- [Configuration](../dev/configuration.md)
- [Observability](../dev/observability.md)
- [Release process](../RELEASE-PROCESS.md)
