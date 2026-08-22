---
title: "DATA-TTL-01 — implementation plan"
version: "0.3.0"
status: "Implementation complete — P7-P9 qualification pending"
document_type: "Implementation plan"
source_of_truth: false
language: "ru"
---

# DATA-TTL-01 — implementation plan

## Execution rule

Работа идёт inward → outward небольшими checkpoint slices. Одновременно
implementation-active только один slice. P2–P5 могут добавлять dormant schema и
code на feature branch, но ни один промежуточный результат не включает TTL в
fresh production template и не считается releaseable capability. Activation
surface и исходное lifecycle evidence были закрыты P6. После уточнения I-22
проект был переоткрыт: P7 исправил export-slot contract, P8 — решение о
byte-identical delivery occurrence, P9 — idle reconciliation runtime. Для
P7–P9 остаются затронутое packaged evidence и финальный freshness gate.

Отдельный go-ahead после review [architecture project](architecture-project.md),
[ADR-0020](../../../ADR/0020-canonical-record-expiration-lifecycle.md) и
[release contract](release-contract.md) получен 2026-08-16. Он разрешает
последовательное выполнение P1–P6, но не частичную production activation.
Принятые I-22 и
[ADR-0021](../../../ADR/0021-stable-reusable-export-slots.md) добавили P7;
[ADR-0022](../../../ADR/0022-revision-significant-identical-export.md) и
[ADR-0023](../../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md)
зафиксировали последующие P8/P9 corrections.

## Slice map

| Slice | Outcome | Activation | State |
|---|---|---|---|
| `P0` | Decision, scope contract и characterization | none | `complete` |
| `P1` | Framework-free application contracts and TCK | none | `complete` |
| `P2` | Additive durable SQLite foundation | disabled behavior only | `complete` |
| `P3` | Atomic lifecycle-aware write/read path | production preset unchanged | `complete` |
| `P4` | Expiry, recovery, scheduling and health | production preset unchanged | `complete` |
| `P5` | Duplicate receipt and explicit upgrade activation UX | opt-in existing installs only | `complete` |
| `P6` | Fresh preset, docs and release evidence | complete capability | `complete` |
| `P7` | Stable sparse reusable export slots | corrected export contract | `implementation_complete`; qualification pending |
| `P8` | Revision-significant identical export delivery | corrected post-hash contract | `implementation_complete`; qualification pending |
| `P9` | Deadline-aware idle runtime and bounded reconciliation checkpoint | lifecycle operational hardening | `implementation_complete`; qualification pending |

## P0 — decision and characterization

### Deliverables

- architecture project, ADR, release scope change, bundle index,
  goal/work-item contract and this plan;
- executable inventory of current canonical write/load/snapshot/projection,
  public ID allocation, ingestion duplicate and startup/admission paths;
- characterization tests for current public row bytes/order, public `time_*`
  nulls, revision behavior, immutable snapshot consistency and disabled startup;
- exact decision on config record shape and clock rollback tolerance inputs;
- characterization of current insert-driven `artifact_revision`/export
  pre-gates and accepted I-20 rule that expiry preserves this behavior;
- accepted I-21 vocabulary and runtime disposition: validity policy produces a
  persisted absolute boundary, while expiration and retention remain separate.

### Exit gate

- user approves architecture project/ADR/contract/plan;
- separate implementation go-ahead is recorded;
- P1 change list fits one reviewable inward-facing scope.

## P1 — application contracts

### Scope

`core/ioc-application` and `core/ioc-application-tck`; no Spring, JDBC, CSV or
runtime activation.

### Deliverables

- lifecycle value objects/results, durable observation identity and injected
  UTC time boundary;
- minimal `RecordValidityPolicy` Strategy with one
  `FixedRecordValidityPolicy`; policy returns an absolute `ValidityDecision`,
  not a persistence or scheduling command;
- lifecycle-aware canonical command/read/reconciliation/activation ports;
- durable allocator, lifecycle state and projection-work abstractions only
  where required by actual use cases;
- reusable TCK for active/due/new-lifecycle/revision/ID invariants.

### Exit gate

- fixed duration is strictly positive;
- one transaction-level `asOf` is explicit in contracts;
- application API cannot persist a business row without required lifecycle
  state when policy is active;
- no speculative factory hierarchy, rules engine or source-owned TTL.

**Status:** completed on 2026-08-16. Evidence is recorded in
[evidence.md](evidence.md#p1--framework-free-application-contracts).

## P2 — durable storage foundation

### Scope

`adapters/adapter-store-jdbc` plus adapter TCK wiring. Behavior remains
observable-compatible while mode is `disabled`.

### Deliverables

- versioned additive SQLite migration for per-artifact lifecycle columns and
  numeric `(_valid_until_epoch_ms, _lifecycle_id)` indexes;
- typed mirror history and compact source-summary tables with retention index;
- singleton activation/clock high-water state and resumable activation progress;
- global lifecycle sequence and durable per-artifact public ID allocator;
- durable required/projected generation work, observation idempotency markers
  and complete typed receipt schema;
- migration/query-plan/invariant integration tests.

### Exit gate

- upgrade fixture with lifecycle absent opens unchanged in disabled mode;
- allocator never derives reuse safety only from active `MAX(id)`;
- lifecycle/history schema remains inside JDBC adapter and preserves configured
  public column order/types;
- no active row is exposed to enabled state with partial lifecycle metadata.

**Status:** completed on 2026-08-16. The foundation remains dormant and the
existing canonical runtime path is unchanged. Evidence is recorded in
[evidence.md](evidence.md#p2--durable-storage-foundation).

## P3 — lifecycle-aware canonical transaction and reads

### Scope

Application/JDBC canonical write and every service-local active read boundary;
projection policy stays outside the repository.

### Deliverables

- atomic insert, renewal, due-close-and-recreate and provenance handling;
- successful canonical commit as the only confirmation point;
- one write-owned effective UTC `asOf` and linearizable confirmation×expiry
  outcome;
- durable public/lifecycle ID reservation with non-reused failed ranges;
- active predicate in repository load, mutable projection and immutable
  multi-artifact snapshot using the correct shared `asOf` semantics;
- existing insert-driven artifact revision, per-observation commit idempotency
  and complete receipt writer rules.

### Exit gate

- deterministic boundary/race tests use controllable clocks and latches, never
  real sleeps;
- `asOf == valid_until` is expired;
- renewal and expiry do not change artifact revision; insert or a new
  lifecycle/public row does;
- failed transaction does not confirm, allocate reusable IDs or publish a
  complete receipt;
- recovery of the same observation after commit does not renew it a second time;
- existing public schemas and `time_* == NULL` are byte/order compatible.

**Status:** completed on 2026-08-16. The lifecycle-aware path and conditional
active reads are implemented and verified, but remain outside production
composition while the persisted state is `DISABLED_COMPATIBLE`. P4 runtime
reconciliation/scheduling is delivered separately; P5 activation and duplicate
receipt integration are not included.
Evidence is recorded in [evidence.md](evidence.md#p3--lifecycle-aware-canonical-transaction-and-reads).

## P4 — expiry, recovery and operations

### Scope

Lifecycle reconciliation in application/JDBC and bootstrap scheduling,
admission, health and diagnostics. CSV remains a projection adapter.

### Deliverables

- indexed keyset-batch archive/delete and independent bounded history cleanup;
- durable projection convergence with one projection per affected artifact/cycle;
- no lifecycle-specific immutable-export event or urgent export nudge;
- idempotent pre-readiness admission sequence: recovery → policy/clock validate
  → activation resume → due expiry → pending projection convergence;
- nearest-deadline daemon scheduler plus periodic correctness backstop;
- `SmartLifecycle`-managed deadline scheduler on an explicitly owned
  `ScheduledExecutorService`, inert until common admission completes; no Spring
  `@Scheduled`, ShedLock or Spring Batch runtime in V1;
- injected system UTC clock, monotonic wait/duration clock, durable high-water,
  `DEGRADED` clamp and fail-closed `DOWN` policy;
- aggregate health, stable diagnostics and aggregate ECS events.

### Exit gate

- crash/fault injection covers history move, delete/projection generation,
  projection replace/ack and restart without resurrection or partially visible
  active data;
- healthy idle reconciliation starts within `5s` of deadline;
- `DEGRADED` keeps intake open only while logical filtering is provable;
- health is read-only and never exposes IOC/source identifiers;
- no manual mutating lifecycle CLI exists.

**Status:** completed on 2026-08-16. Runtime reconciliation, durable mutable
projection convergence, safe clock, common admission, daemon scheduling,
aggregate health/diagnostics and bounded history retention are implemented.
The packaged and classpath presets deliberately remain
`DISABLED_COMPATIBLE`; fixed validity, upgrade activation and duplicate receipt
reuse remain P5. Evidence is recorded in
[evidence.md](evidence.md#p4--expiry-recovery-scheduling-and-health).

## P5 — duplicate receipt and upgrade activation UX

### Scope

`adapter-ingest`, bootstrap config/admission and packaged upgrade path.

### Deliverables

- content identity separated from a durable observation/attempt identity, so a
  later identical delivery confirms records while recovery remains idempotent;
- complete receipt fast path keyed by source identity and processing-policy
  fingerprint, with `30d` retention and ordinary ETL fallback on missing/stale
  receipt;
- strict `ioc.lifecycle.validity` configuration with `disabled|fixed` mode and
  positive fixed duration;
- persisted one-way activation and idempotent `existing-records: expire`;
- two-step compatibility-start then explicit-cutover procedure;
- daemon and stateful oneshot/export enforcement.

### Exit gate

- legacy 0.2.0 fixture can be interrupted at activation boundaries and resume;
- activation closes all legacy rows with history, activation-cycle and
  projection work without advancing insert-driven revision, can yield an empty
  active set and never auto-replays archives;
- old source ledgers do not block a new lifecycle after new accepted input;
- startup rejects fixed-without-positive-TTL and disabled-after-activation with
  stable diagnostics;
- exact config + both SQLite DB form the documented rollback point.

**Status:** completed on 2026-08-16. Content identity and durable observation
identity are separated, complete current-policy receipts provide an
ETL-skipping confirmation path, and legacy activation is explicit, resumable
and one-way. Daemon and stateful one-shot/export use common lifecycle
admission. Classpath and packaged presets deliberately remain `disabled`; the
fresh-install `fixed/12h` cutover and release evidence remain P6. Evidence is
recorded in [evidence.md](evidence.md#p5--duplicate-receipt-and-explicit-upgrade-activation).

## P6 — release closure

### Deliverables

- fresh-install packaging template fixed at `12h`; upgrade/classpath default
  remains `disabled`; history/receipt retention defaults to `30d`;
- operator guide for two-step activation, destructive legacy expiry, possible
  empty output, clock prerequisite, health and rollback boundary;
- affected English capability docs, module README, architecture/module maps,
  generated diagnostics/config references and release notes;
- packaging fresh-install/upgrade/rollback smoke;
- 100k simultaneous-expiry reference scenario and stored environment profile,
  query plans, throughput/drain baseline and justified regression threshold;
- targeted tests, `make docs`, full `make verify` and refreshed status/evidence.

### Release gate

P6 may enable the fresh production preset only after P1–P5 evidence is green.
The capability is not merge/release complete while any required crash/race,
read-path, migration, packaged rollout or performance evidence is missing.

**Status:** completed on 2026-08-19. In addition to the fresh preset, published
documentation, packaging contracts and rootless 100k profile, a privileged
systemd stand verified a v0.2.0 compatibility upgrade, explicit destructive
activation, fixed-TTL expiry, duplicate reappearance with new IDs, independent
history/receipt retention, activation rollback, release rollback and a clean
fresh installation. The verified current release remains installed and healthy
with the production `fixed/12h` preset. Evidence is recorded in
[evidence.md](evidence.md#privileged-packaged-systemd-stand-2026-08-1819).

P6 public-ID results are retained as characterization of the current
implementation, not as acceptance of the corrected I-22 contract.

## P7 — stable reusable export slots

### Scope

`core/ioc-application` export contracts, `adapter-store-jdbc` dataframe schema
and snapshot implementation, `adapter-sink-csv` mapping, bootstrap wiring and
affected tests/docs. TTL policy, expiry/history, provenance and source-owned ID
semantics stay unchanged.

### Deliverables

- replace the external-ID interpretation with an export-owned `export_slot`
  value; canonical primary key and `_lifecycle_id` stay internal/non-reusable;
- add a same-dataframe-DB registry scoped by `(profile, artifact)` with unique
  lifecycle→slot and slot→lifecycle ownership, free-slot index and durable
  high-water/generation state;
- seed current active lifecycles from their current exported IDs without
  renumbering; fail closed on ambiguous/colliding/invalid seeds;
- reconcile at eligible export only: release vanished assignments, preserve
  survivors, allocate smallest holes to new lifecycles and high-water ranges to
  the remainder;
- project resolved `export_slot AS id` and order by slot where the existing
  artifact contract requires it; artifacts without external `id` are unchanged;
- detect canonical generation changes before slice publication and use the
  existing retry/saga path instead of publishing a mixed mapping;
- include slot policy/version in export plan/schema fingerprint without
  changing canonical artifact identity epoch solely for this correction;
- update published docs and repeat focused, 100k and packaged evidence.

### Performance and architecture constraints

- no per-row SQL allocation, `MAX(active.id)+1`, dense `ROW_NUMBER()`
  compaction or full active-set JVM materialization;
- bounded set-based/staged operations with query-plan evidence;
- no export-slot dependency from lifecycle/expiry/history/provenance paths;
- no new Maven module, Java library, event, scheduler or service-DB authority.

### Exit gate

- `A=1,B=2,C=3`; after `A/B` expiry and arrival of `D`, output is `D=1,C=3`;
  after arrival of `E`, output is `D=1,E=2,C=3`;
- survivors never renumber, gaps remain until consumed, and simultaneous new
  rows receive deterministic ascending available slots;
- old immutable slices retain their original mappings while later slices may
  reuse the same slot for another lifecycle;
- restart/failure/race and migration tests pass, including artifacts without
  `id` and source-ID separation;
- new 100k and packaged fresh/upgrade/rollback evidence is recorded;
- `make docs`, targeted tests and fresh full-reactor verification pass on final
  `HEAD`.

**Status:** implementation and automated evidence complete on 2026-08-19;
packaged qualification and final fresh reactor gate pending. Detailed design:
[export-slot-correction.md](export-slot-correction.md).

## P8 — revision-significant identical export delivery

### Scope

The pure export change detector, forward formation, crash recovery, terminal
observability and affected architecture/capability documentation. Canonical TTL,
revision writes, export-slot allocation and publish-ledger authority remain
unchanged.

### Deliverables

- treat a candidate as redundant only when profile artifacts, `plan_hash`,
  public hashes and manifest coverage revisions equal durable progress;
- complete and emit the existing `SliceCompleted` fact when public bytes match
  but at least one covered insert-driven revision advanced;
- use the same policy during recovery without rereading canonical storage;
- expose the terminal export status in structured logs;
- add ADR-0022 and regression tests for forward, recovery and the still-valid
  equal-revision `SKIPPED` case.

### Exit gate

- a lifecycle returning after TTL produces a new immutable slice and publish
  opportunity even if its export slots and CSV bytes equal an older slice;
- expiry, renewal and active confirmation still do not create export work;
- forward execution and recovery produce the same terminal decision;
- focused tests, documentation gate, full reactor verification and packaged
  stand evidence pass.

**Status:** implementation and focused automated evidence complete on
2026-08-22; packaged stand evidence and final fresh reactor gate pending.

## P9 — bounded idle lifecycle runtime

### Scope

Correct the physical reconciliation runtime introduced by P4 without changing
TTL semantics, export triggers, active-read filtering or the five-second
correctness bound. The accepted decision is
[ADR-0023](../../../ADR/0023-bounded-lifecycle-reconciliation-runtime.md).

### Deliverables

- event/backstop paths refresh the durable nearest deadline and only a due
  deadline starts reconciliation;
- dataframe format v6 migrates the latest legacy cycle into a constant-size
  `lifecycle_reconcile_state` checkpoint and freezes the old journal;
- history cleanup uses an independent admission-gated scheduler with a `1h`
  default cadence and immediate bounded follow-up while eligible rows remain;
- empty reconciliation and projection checks do not produce INFO events;
- configuration, health, migration, scheduling, concurrency and observability
  regression tests plus operator/developer documentation are aligned.

### Exit gate

- 10,000 empty backstop refreshes cause no reconciliation and no durable cycle
  sequence change;
- a lost event remains recoverable within `5s` and a due deadline cannot run
  overlapping reconciliation;
- v5 upgrade keeps the last legacy result visible through health and all later
  cycles update exactly one state row;
- retention backlog is drained in bounded transactions independently of expiry;
- focused tests, docs validation and `make verify` pass on the final `HEAD`.

**Status:** implementation and automated evidence complete on 2026-08-23;
packaged stand qualification and a fresh gate on the final committed `HEAD`
remain pending.

## Required test matrix

| Area | Mandatory evidence |
|---|---|
| Lifecycle semantics | boundary, renewal, prospective policy change, expiry→new internal lifecycle ID, public `time_*` unchanged |
| Export slots | survivor stability, smallest-hole reuse, gaps/no compaction, deterministic batches, immutable historical mappings |
| Transaction/race | SQLite confirmation×expiry ordering, rollback, internal ID non-reuse, slot generation consistency, no lost observation |
| Migration/recovery | 0.2.0 fixture, activation fault injection, restart, projection convergence |
| Read surfaces | canonical load, mutable dataframe, immutable slice with active-only one-`asOf` behavior |
| Export trigger | expiry leaves `artifact_revision` unchanged; next new-row trigger exports current active membership |
| Runtime/config | daemon, stateful oneshot/export, defaults, one-way transition, clock rollback states |
| Load | 100k same deadline plus slot release/reassignment, start ≤5s, bounded batches/memory/transactions, no starvation, eventual drain |

## Risk controls

- No `ttl=0` migration shortcut.
- No source/provenance join on every active read.
- No per-row timers, full-table polling or full-set JVM materialization.
- No per-read decay formula or reuse of public `score` as lifecycle state.
- No mapping of ordinary expiry to STIX `revoked` or OpenCTI `detection`.
- No `@Scheduled`, ShedLock or Spring Batch without the measured scale or
  deployment condition that justifies another runtime subsystem.
- No second write after canonical commit to attach lifecycle metadata.
- No `MAX(active.id)+1` as canonical or export-slot authority.
- No dense renumbering or coupling of `export_slot` to canonical/lifecycle ID.
- No in-memory event as expiry/projection correctness authority.
- No use of public `time_first_seen`/`time_last_seen` as technical timestamps.
- No release claim from aggregate coverage or happy path alone.

## Evidence location

P0 documents stay in this directory. Implementation evidence should be added as
`evidence.md` or a small `evidence/` subtree inside this bundle when P1 begins;
generated build reports remain untracked under their normal `target/` paths and
are referenced by reproducible command/commit/environment metadata.
