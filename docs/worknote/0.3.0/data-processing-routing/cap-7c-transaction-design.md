# CAP-7C transaction visibility and canonical adapter

Status: in progress, contract baseline implemented; backend and publication
protocol are not selected. No production transaction, schema, configuration or
deployed state has changed. This is an execution design for
[CAP-7C](data-processing-capacity-plan.md#cap-7--conditional-follow-on-architecture-decisions),
not an accepted ADR or capacity acceptance.

Entry HEAD: `39237cb3f9edf7763aa1799c9d7cd98d6adff69d`,
branch `module/platform/router`. Entry verify and PMD evidence was fresh for
that HEAD. Dataframe schema is v12; service schema is v14. The pinned driver is
sqlite-jdbc 3.53.2.0. Subsequent edits need their own final quality gates.

## Trigger and unchanged acceptance limits

The [final CAP-7B packaged screen](qualification/capacity/cap-7b-production.json)
passed its canonical, provenance, mutable-projection and immutable-slice oracles
but failed the local-time and writer limits. Its maximum PROMOTION occupancy
was 11.579311126 s, across five completed admissions. Local time was a
conservative 62.005 s upper bound. These are one-screen observations, not a
sustained mixed-load qualification or an isolated import measurement.

The adopted maximum writer occupancy is 5 s per admitted canonical/control
transaction. Eligible control wait must remain within 10 s under declared
sustainable intake, and a ready profile must start within 2 s once its own
prerequisites/resources are available. The 100k/1m local-time limits and original
2-CPU, heap, RSS and cgroup limits also remain unchanged. G6 is NOT_ACCEPTED.
CAP-7D's reducer problem is independent of this transaction track.

SQLite WAL supports simultaneous readers and a writer, but still has only one
writer per database. Smaller Java batches inside a transaction cannot release
that writer. A pinned read transaction retains its committed snapshot.
See the primary [isolation](https://www.sqlite.org/isolation.html) and
[WAL](https://www.sqlite.org/wal.html) documentation.

## Implemented contract baseline

`JdbcCanonicalImportWriterContractIT` now pauses a real SQLite fan-out promotion
at BEFORE_COMMIT, after rows, aliases, source summaries, revisions, preferred
slot, warning/rejection evidence and receipt children have been written.
Another connection cannot see its pending rows, aliases, slots or receipt.
The real `JdbcCanonicalMatchPlanner` finds no pending match. A pinned reader
keeps the old snapshot after commit, while a fresh matcher sees the committed
key. Reopening the snapshot exposes both artifacts, their revisions and slot.
Deleting the sealed workspace does not prevent receipt-only replay; it does
not duplicate provenance, and warning/rejection evidence remains available.

The test owns a bounded worker and two timed latches, has a containing JUnit
timeout, releases the writer in `finally` and asserts worker termination.
It extends an existing Failsafe/TCK implementation; it does not change the
reviewed suite universe or quality floors. Existing failure-at-every-phase,
registration-rank, TTL, slot-conflict and receipt-identity regressions remain.

This test qualifies the current SQLite contract only. It does not implement
hidden committed chunks, prove short writer occupancy, exercise an actual
process kill, or qualify a concurrent replacement adapter.

Contract commit: `b77b1af7953baa986a92f7ca42fbfa2e8c5f146b`.
The focused `JdbcCanonicalImportWriterContractIT` run executed 22 tests with
zero failures/errors/skips. Run it with:

```bash
make test-one MODULE=adapters/adapter-store-jdbc TEST=JdbcCanonicalImportWriterContractIT
```

Final repository checks are `make verify`, `make pmd-analysis` and `make docs`;
their exact-HEAD freshness comes from `make context`. Passing these checks does
not select a backend or close the C1–C6 gates below.

## Current owners and migration seams

| Owner | Existing responsibility | Requirement for CAP-7C |
|---|---|---|
| `JdbcCanonicalLifecycleWriter` | Validate private input, reserve IDs, confirm one artifact, publish marker/receipt and revision in one transaction | Preserve artifact-level document progress and receipt identity; move only physical work, not authority |
| `JdbcCanonicalImportWriter` | Match, merge, reject, mutate all fan-out artifacts, reconcile preferred slots, persist complete delivery evidence atomically | Preserve delivery-wide all-or-none publication and receipt-only recovery |
| `JdbcCanonicalMutationSession` / `JdbcCanonicalMatchSession` | Shared matching and mutation semantics with transaction-local read-your-writes | Reuse these rules; changing storage must not create a second mutation engine |
| `JdbcOrderedFieldStore` | Registration rank and per-field origins | Include metadata-only changes in conflict validation; public revision alone cannot detect them |
| `JdbcLifecycleTransactions` | Acquire ACTIVE ownership via the lifecycle-control row | Remove or replace the physical global lock only with an explicit correctness protocol |
| `JdbcLifecycleClock` | Persist safe UTC high-water inside owned transactions | Retain nondecreasing time and clamp/failure behavior without a long global lock |
| `JdbcWriterAdmission` | Non-preemptive shared admission and wait/hold metrics | Admit bounded physical work while retaining logical promotion order; report both physical and logical delays |
| `JdbcSnapshotSliceReader` / `JdbcExportSlotRegistry` | One export snapshot, sparse namespace slots, bounded readers | Read committed versions only; preserve slot ownership and one snapshot across artifacts |
| Expiry, history, projection and receipt stores | Active membership, generations, retained recovery evidence | Observe the same committed visibility as canonical reads and matching |

ID reservation is intentionally outside the large canonical transaction.
Aborted or invalidated attempts can consume ranges, but canonical public and
lifecycle IDs must never be reused. Requested import IDs remain export slots.
The service journal coordinates delivery; it must not become canonical commit
authority or carry the visibility flip.

## Candidate comparison

| Dimension | Staged/versioned SQLite | Concurrent transactional adapter |
|---|---|---|
| Physical mechanism | Commit bounded hidden versions, then atomically publish their unit | Allow unrelated transactions concurrently with database locking/MVCC |
| Atomicity | Final header, receipt and visibility transition in one dataframe transaction | One atomic canonical transaction, or an explicitly qualified publication protocol |
| Required code changes | Version-aware rows, aliases, origins, slots, history, readers, matching, receipts and cleanup | New adapter/dialect, schema/migration, concurrency ownership, clock/slot/identity locking and runtime composition |
| Current kernel reuse | Adapt persistence surfaces of the existing match/mutation kernel | Extract genuinely shared semantics; keep dialect and resources in adapters |
| Main risk | Pending/old-version leakage, invalidation starvation, index/read amplification and version retention | Changed conflict/rank ordering, global locks surviving the swap, aggregate DB-service resource cost and operational migration |
| Five-second gate | Every staging chunk and final publication must satisfy the physical occupancy limit | A long PostgreSQL transaction is not an automatic pass; distinguish transaction duration and conflicting lock occupancy, and retain the adopted gate until an explicit owner decision |

PostgreSQL is a candidate, not an approved backend. Its locking model allows
different contention scopes, but a port retaining the Java admission mutex and
the singleton lifecycle-control update would still serialize this workload.
See [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html).
The application and database service must share the declared total CPU/memory
budget during comparison; giving each process the full budget is not equivalent.

The open deployment constraint is whether SQLite remains mandatory or a
PostgreSQL canonical adapter may be considered. A bulk insert or generic MVCC
demo cannot substitute for a qualified canonical adapter. No dependency/module
or production configuration is added before this choice.

## Minimum staged/versioned protocol

The following is a candidate protocol checklist, not permission to activate it.
Its unresolved points must be settled in an ADR before a production migration.

1. Register a logical publication unit with immutable observation/delivery,
   contract, stage digest, schema/policy pins and a fencing token. Ordinary
   documents keep per-artifact units; imports keep one cross-artifact unit.
   Resume cannot invent a new observation or bypass ordered promotion.
2. Read a committed base snapshot and record a bounded dependency vector.
   Conflict epochs must include row, alias, rank/origin, renewal, expiry and
   relevant slot changes. `artifact_revision` is insufficient: TTL-only and
   metadata-only changes need not advance it.
3. Prepare and commit bounded hidden versions. A new row's pending version is
   absent from active reads; an update/clear must retain the previous committed
   value until publication. A flag on the existing visible row cannot provide
   this contract. The current `id` primary key also cannot hold both versions;
   a physical version identity must remain separate from logical canonical IDs.
4. Persist hidden aliases, source summaries, field origins, history, slot
   outcomes and receipt children under the same unit/fence. Any public reader
   or matcher must join committed visibility. Index plans must retain CAP-1's
   selective alias lookup; resolving versions must not restore an artifact-wide
   scan per candidate.
5. Under short physical writer ownership, revalidate the fence, dependencies,
   lifecycle state and effective-time constraints. Publish visibility, revision,
   projection generation and the complete canonical receipt atomically. A
   last-minute scan/update of all N rows merely recreates the long transaction;
   its work and query plans must be included in the occupancy measurement.
6. Recovery before publication resumes/rebuilds or cancels hidden state without
   exposing it. Recovery after publication finalizes from the canonical receipt
   without parsing CSV/source or requiring the private stage. Missing/corrupt
   receipt evidence fails closed. Cleanup cannot remove committed data still
   referenced by a reader, active lifecycle, receipt or deadline anchor.

All readers need the same visibility contract: canonical lookup, aliases,
mutable projection, immutable slices, nearest-deadline queries, expiry,
history/source summaries and receipt replay. Changing only export SQL is unsafe.
Transparent SQLite views/triggers are not a drop-in replacement for the current
writer: existing mutation code relies on affected-row counts and physical
constraints. Any such technique needs exact-driver regressions first.

### Decisions the final protocol must resolve

| Question | Required disposition |
|---|---|
| When is safe effective UTC sampled? | Specify publication linearization and renewal behavior; private preparation time is not confirmation authority |
| What if a matched lifecycle expires while versions are prepared? | Revalidate and recompute the applicable new-lifecycle outcome; do not resurrect the expired lifecycle or its slot |
| How are N deadline updates avoided at the flip? | Qualify an indexed representation of absolute UTC validity; a shared publication header must not turn TTL into delivery-scoped business identity |
| What if expiry, rank/origin or slot reconciliation changes dependencies? | Fail closed/rebase with the same observation and policy; do not publish stale matching or slot decisions |
| How does sustained invalidation make finite progress? | Prove a bounded conflict strategy under declared sustainable intake; an indefinite artifact fence that blocks all control work defeats CAP-7C |
| How are slot preference and namespace uniqueness preserved? | Qualify pending reservations, expiry/release, survivor preservation and lowest-hole fallback in one publication contract |
| What do metrics count? | Record physical chunk/flip occupancy, logical publication latency, conflict rebuild work and eligible control/export wait separately |

A coarse epoch may be useful for the first correctness experiment, but clock
high-water reads alone must not invalidate all canonical plans. A durable
logical promotion fence is also not permission to block unrelated maintenance
or redefine it as ineligible for the latency gate. If the protocol cannot meet
correctness and finite-progress targets together, reject it rather than falling
back to the current oversized transaction silently.

## Implementation sequence and promotion gates

| Slice | Deliverable | Gate |
|---|---|---|
| C0 contract baseline | Current real SQLite pending visibility, matching, pinned snapshots and receipt-only replay | Existing suite plus new deterministic regression; implemented |
| C1 architecture choice | Deployment/backend constraint, visibility/clock/conflict decisions and proposed ADR | No unresolved authority/linearization decision in production code |
| C2 persistence experiment | Isolated candidate implementation behind existing ports, with exact driver/schema/resource identity | Reuse kernel semantics; no production activation; selective query plans |
| C3 complete integration | Ordinary writer, import, active reads, lifecycle, slots, revisions and receipts use the chosen model | Same semantic oracle as corrected SQLite; no alternate runtime mutation engine |
| C4 faults/concurrency | Crash/cancel/conflict/restart matrix below | Pending never visible; no half delivery; rank/TTL/slot/recovery equivalence |
| C5 capacity comparison | Corrected baseline and candidates, identical workload/results/limits, complete-service metrics | Physical occupancy, eligible waits and total resources meet adopted limits; retain failed cells |
| C6 migration/operations | Drained upgrade, rollback backup/restore, readiness/health, durable cleanup and deployment procedure | Full adapter/TCK and operational qualification; accepted ADR before activation |

No stage is accepted merely because ordinary CSV output is equal. The oracles
must inspect canonical/lifecycle identities, aliases, ranks, provenance,
revisions, generations, slots, receipt fields and recovery results.

## Fault and concurrency matrix

| Boundary | Oracle |
|---|---|
| Before/after each hidden chunk | No pending matches/exports, previous committed rows remain visible; recovery preserves logical unit/fence |
| After public mutations and receipt staging, before flip | All fan-out facts remain invisible together; pinned readers retain their prior snapshot |
| Flip failure / cancellation | Rollback leaves no partial visibility or completed receipt |
| Commit before service-ledger update | Receipt-only recovery without private input; no second mutation/provenance/ID effect |
| Old attempt resumes after new fence | Stale publisher cannot commit or delete the new attempt |
| Same-key document/import, reverse preparation completion | Supported KEEP_FIRST, registration-rank and merge/conflict outcomes remain unchanged |
| TTL equality / expiry during preparation | Equality is expired; correct lifecycle and alias membership, no slot resurrection |
| Preferred-slot collision / reconcile during preparation | Namespace-scoped uniqueness, survivor preservation, correct lowest-hole fallback and receipt evidence |
| Metadata-only origin update / renewal-only observation | No lost ordered update or TTL renewal; revision/generation rules unchanged |
| Reader held across publication and cleanup | One snapshot across artifacts/aliases/slots; bounded WAL/version retention and reader termination |
| Quota full / corruption / shutdown during hidden work | Fail closed, no visible partial unit, durable retry/cancellation evidence and owned-resource cleanup |

## Migration and evidence rules

Drain intake and logical publications before migration; back up both databases
and owned files together. Schema/catalog/recovery pins must reject incompatible
binaries. Rollback restores the coordinated backup; changing `user_version`
or copying only the dataframe file is unsupported. A database replacement also
needs backup/restore, credentials/configuration, connection limits, readiness,
service lifecycle and total resource qualification.

Retain exact runtime/input/configuration/driver/schema identities, all failed
screens, query plans, physical hold/wait counts, complete-service time/CPU,
allocations, heap/RSS, WAL/version/workspace bytes and cleanup outcomes. Do not
infer accepted control latency from a source-only or generic bulk experiment.
Installed service state is outside this worktree change.
