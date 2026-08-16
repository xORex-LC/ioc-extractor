# Canonical record lifecycle

Canonical record lifecycle keeps only currently valid IOC records in active
storage while preserving bounded historical evidence. It starts after an
accepted canonical transaction and ends when the persisted `valid_until`
boundary is reached. It does not model source withdrawal, downstream firewall
acknowledgement, STIX revocation or per-record scheduler jobs.

## Runtime flow

```text
accepted observation
  -> validity policy returns absolute valid_until
  -> one canonical SQLite transaction inserts, renews or closes-and-recreates
  -> active-only mutable projection

nearest deadline hint OR periodic backstop
  -> indexed bounded archive/delete batches
  -> durable projection generation
  -> active-only mutable projection convergence

independent retention pass
  -> indexed bounded history/receipt deletion
```

The event/timer path only reduces latency. SQLite deadlines, projection
generations and reconciliation cycles are the correctness authority after lost
events or process restarts.

## Boundaries

- `core/ioc-application` owns storage-neutral lifecycle values, the fixed
  validity strategy, admission, activation, reconciliation and retention use
  cases plus their ports.
- `adapter-store-jdbc` owns the SQLite representation, atomic SQL, typed history,
  receipts, monotonic allocators, active reads and indexed cleanup.
- `bootstrap/ioc-app` binds strict configuration, injects UTC and monotonic
  clocks, orders pre-admission recovery and owns the aggregate scheduler and
  health indicator.
- CSV remains a projection adapter. Lifecycle does not make CSV or immutable
  export a second source of truth.

Lifecycle state stays in the dataframe database because confirmation, active
membership and history movement must share the business-row transaction. The
service database continues to hold ingestion/export/sync coordination only.

## Invariants

1. **Validity belongs to one canonical row lifecycle.** Provenance confirms the
   row but does not own or independently schedule TTL.
2. **The active interval is half-open.** A row is active only while
   `valid_until > asOf`; equality is already expired on every read surface.
3. **Confirmation is commit-owned.** Parsing or preparation alone cannot renew
   validity. One write-owned effective UTC value decides renewal versus
   close-and-recreate.
4. **Expired identity is never reused.** Reappearance creates a new lifecycle
   and service-owned public ID. Durable allocators survive active and history
   deletion.
5. **Expiry is not an insert.** It does not advance `artifact_revision` or cause
   an immutable export by itself. The next new-row-driven export observes the
   current active membership.
6. **Public schemas stay stable.** Internal lifecycle columns are excluded from
   CSV/export; `time_first_seen` and `time_last_seen` remain business fields and
   remain `NULL` until a separate mapping contract is approved.
7. **Work is bounded.** Deadline and retention indexes feed fixed-size SQLite
   transactions; there is no timer, thread or job per IOC.

## Failure and recovery

| Situation | Behavior | Correctness source |
|---|---|---|
| Crash during expiry | Interrupted cycle is failed on admission and due rows are reconciled again | active row plus reconcile journal |
| Projection failure after archive | Active reads already exclude the row; durable generation remains pending until projection converges | dataframe DB generation state |
| Lost deadline/event hint | Nearest-deadline lookup and periodic backstop rediscover work | indexed absolute deadline |
| Small UTC rollback | Effective time clamps to the durable high-water; health is `DEGRADED` | lifecycle clock control |
| Material or prolonged rollback | Stateful admission/readiness fails closed with `DOWN` | lifecycle clock policy |
| Upgrade with legacy rows | Compatibility start is non-destructive; explicit resumable activation archives legacy rows before intake/export | activation state and progress |
| History retention failure | Active lifecycle remains correct; a later bounded pass retries cleanup | retained history plus scheduler |

## Extending the policy

A future type/risk/source-aware lifetime extends `RecordValidityPolicy` and its
configuration/fingerprint mapping. Persistence still receives an absolute
`valid_until`, so storage, active predicates and reconciliation do not depend on
the policy formula. Exporting a deadline or mapping lifecycle dates into public
business fields requires a separate versioned consumer contract.

## Sources of truth

- Decision: [ADR-0020](../ADR/0020-canonical-record-expiration-lifecycle.md).
- Application contracts: `core/ioc-application/.../artifact/lifecycle`.
- SQLite schema and SQL: `adapter-store-jdbc` dataframe migration v4 and
  lifecycle adapter tests.
- Production assembly/defaults: `AppConfig`, `IocProperties` and the classpath
  `application.yml` in `bootstrap/ioc-app`.
- Operator behavior: [canonical record lifecycle guide](../guides/canonical-record-lifecycle.md).
- Reproducible runtime evidence: `tools/dev/lifecycle-smoke.sh`.

## When to update this document

- the validity owner, active predicate or canonical transaction boundary changes;
- activation, expiry, retention or clock recovery semantics change;
- expiry starts affecting artifact revision or immutable-export triggering;
- a new supported validity policy or downstream lifecycle contract is added.

## Related documents

- Storage mechanics: [storage.md](storage.md).
- Export behavior: [artifact-export.md](artifact-export.md).
- Ingestion confirmation point: [ingestion.md](ingestion.md).
- Module map: [MODULARIZATION.md](../MODULARIZATION.md).
