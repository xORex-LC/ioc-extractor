# Canonical record lifecycle

This guide explains how an operator enables and observes fixed record validity.
The mechanism controls which canonical IOC records are active inside
ioc-extractor. It does not deliver removals to DNS/firewall systems and does not
interpret a missing row in one source document as withdrawal.

## What to expect

Each successful canonical confirmation grants a record the configured fixed
lifetime. A repeated accepted observation renews that active lifecycle. If no
confirmation arrives before the deadline, the record is removed from active
SQLite membership, copied to bounded history and removed from mutable CSV
projections. An observation after expiry creates a new lifecycle and a new
non-reusable canonical row ID. The `id` exposed by an immutable export is a
separate reusable export slot: surviving active rows keep their slot, while a
slot released by an expired lifecycle may later be assigned to a new lifecycle.

The active set may legitimately be empty. Every source document is treated as
an incomplete observation batch: records omitted from the latest document keep
their remaining lifetime and are not withdrawn early.

Expiry refreshes mutable `dataframe/*_generated.csv` projections, including
header-only files when no active rows remain. It deliberately does **not** create
an immutable export slice or advance the insert-driven export revision. A later
accepted source that inserts new rows triggers the normal export path, and that
slice contains only records active at its shared snapshot time.

## Fresh installation

The production packaging template enables `fixed` validity with a `12h` TTL.
History and complete confirmation receipts are retained for `30d`. A clean
database contains no legacy rows, so the safe `existing-records: reject` guard
allows activation without destructive migration.

The embedded classpath default remains `disabled`. Running an unpackaged jar
without the production template therefore does not unexpectedly activate TTL.

## Upgrade an existing installation

Do not combine binary rollout and destructive legacy expiry in one restart.

1. Back up the active immutable application, complete operator configuration,
   `ioc-dataframe.db` and `ioc-service.db` as one recovery point. Include SQLite
   side files or use a SQLite-consistent backup while the service is stopped.
2. Deploy the new binary with the existing configuration still set to
   `mode: disabled` and `existing-records: reject`. The installer preserves the
   operator file and writes the new fresh-install template as
   `application.yml.new` for review.
3. Start once and require local health `UP`. This compatibility start applies
   additive schema migrations but keeps every legacy row active.
4. Stop intake and optional synchronization. Change the operator configuration
   to `mode: fixed`, choose a positive `fixed-ttl`, and set
   `existing-records: expire`.
5. Restart. Admission archives and removes all pre-activation rows before
   intake, stateful oneshot/export work or lifecycle scheduling opens. Empty
   active storage and header-only projections are valid outcomes.
6. Require health `UP`, inspect aggregate lifecycle counts and resume producers.
   New accepted observations repopulate the active set.

Activation is durable and one-way for that dataframe database. After activation,
changing only the configuration back to `disabled` fails startup.

## Health and clocks

The service uses the host system UTC clock behind an isolated lifecycle clock
boundary. Keep NTP/time synchronization healthy and never roll the clock back to
extend IOC validity.

```bash
ioc health --component lifecycle
ioc health --json
curl --fail --silent \
  http://127.0.0.1:8081/actuator/health/lifecycle
```

The lifecycle component exposes aggregate counts only; it never returns IOC or
source identifiers. Important fields are:

| Field | Meaning |
|---|---|
| `activation` | `DISABLED_COMPATIBLE`, `ACTIVATING` or `ACTIVE` persisted state |
| `clock` | safe, temporarily clamped or unsafe effective UTC state |
| `dueRecords` | physically present rows already past the active boundary |
| `historyRecords` | retained closed lifecycle snapshots |
| `pendingProjections` | mutable artifact projections still needing convergence |
| `dueBacklogAgeMs` | age of the oldest due row not yet archived |
| `artifacts` | aggregate stored/due/history counts per artifact |

`DEGRADED` is acceptable only for a short recoverable clamp or convergence lag.
`DOWN` after a clock rollback is fail-closed: correct the host clock and restart
only after UTC is trustworthy. Do not edit lifecycle timestamps or control rows.

## Retention and capacity

Expiration and retention are separate. Expiration stops active use immediately;
history retention later removes audit snapshots in indexed bounded batches.
Receipt retention only controls the ETL-skipping confirmation cache. Deleting
history never resets lifecycle or canonical-ID allocators.

Export slots follow a different rule. Expiration and history retention do not
renumber survivors. At the next otherwise eligible export, vanished lifecycles
release their slots and new lifecycles take the smallest free positive slots;
the remaining slots come from a durable high-water mark. Gaps are valid until
new rows consume them, and completed historical slices are never rewritten.

The defaults are designed for tens of thousands to roughly one hundred thousand
active rows:

- `backstop-interval: 5s` is the correctness retry when a deadline hint is lost;
- `batch-size: 1000` bounds each SQLite writer transaction;
- `history-cleanup-interval: 1h` controls independent cleanup discovery; an
  existing backlog continues through immediate bounded follow-up tasks;
- history and receipt retention are `30d`.

An empty backstop tick only refreshes the indexed nearest deadline. It does not
start a reconcile cycle or write a heartbeat row. Material reconciliation and
cleanup outcomes remain visible in logs and aggregate health.

Use aggregate health and query-plan/load evidence before changing batch size.
Do not create a job per record and do not use SQLite file size as proof of
retention: deleted pages may remain in the file until database maintenance.

## Rollback boundary

Before activation, normal application rollback may use the matching binary and
configuration while preserving the additive database schema. After activation,
rollback means stopping the service and restoring the matching pre-activation
application, configuration and **both** SQLite databases together. Restoring
only one database or only the YAML creates an unsupported mixed point.

Generated CSV/export files can be rebuilt, but files already delivered remotely
or sources moved after the backup require separate reconciliation.

## Test on a disposable host

Repository-local validation needs no root privileges:

```bash
make lifecycle-smoke
make lifecycle-load
```

The harness sends data through normal daemon ingestion, verifies active-to-history
movement, bounded retention, projection convergence and non-reused internal
identities, and writes its environment/query-plan/throughput report under
`.dev/`. Focused export tests separately verify stable/reusable slots and
immutable historical mappings. A final release candidate still needs the
packaging fresh-install/upgrade/rollback test on a disposable systemd host.

See also the [deployment guide](deployment.md),
[configuration reference](configuration.md) and
[daemon runbook](daemon-operations.md).
