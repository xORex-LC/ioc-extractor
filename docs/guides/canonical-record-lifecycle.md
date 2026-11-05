# Canonical record lifecycle

[Русская версия](ru/canonical-record-lifecycle.md)

This guide explains how an operator enables and observes fixed record validity.
The mechanism controls which canonical IOC records are active inside
ioc-extractor. It does not deliver removals to DNS/firewall systems and does not
interpret a missing row in one source document as withdrawal.

## What to expect

Each successful canonical confirmation grants a record the configured fixed
lifetime. A repeated accepted observation renews that active lifecycle. At the
deadline the record stops being active, including exact equality. Bounded
cleanup later moves it to history and updates mutable CSV projections; those
files may briefly lag behind canonical active reads. An observation after expiry
creates a new lifecycle and a new non-reusable canonical row ID. The `id`
exposed by an immutable export is a
separate reusable export slot: surviving active rows keep their slot, while a
slot released by an expired lifecycle may later be assigned to a new lifecycle.

The active set may legitimately be empty. Every source document is treated as
an incomplete observation batch: records omitted from the latest document keep
their remaining lifetime and are not withdrawn early.

Expiry refreshes mutable `dataframe/*_generated.csv` projections, including
header-only files when no active rows remain. It deliberately does **not** create
an immutable export slice or advance the export revision. A later
public-data change, such as an inserted row or a managed-import field update,
admits the normal export path. That slice contains only records active at its
shared snapshot time. If expired IOC
values return as new lifecycles, the service completes and delivers a new slice
even when its CSV bytes match an older slice; the newer covered revision makes
the delivery occurrence significant.

Changing `fixed-ttl` applies to later accepted confirmations. Existing deadlines
stay unchanged until another confirmation; omitted rows are not renewed. A
retry of the same already committed observation does not grant a fresh TTL.

## Fresh installation

The production packaging template enables `fixed` validity with a `12h` TTL.
History and complete confirmation receipts are retained for `30d`. A clean
database contains no legacy rows, so the safe `existing-records: reject` guard
allows activation without destructive migration.

The embedded classpath default remains `disabled`. Running an unpackaged jar
without the production template therefore does not unexpectedly activate TTL.

## Upgrade an existing installation

If fixed lifecycle is already active, keep `fixed` enabled and follow the
[deployment procedure](deployment.md); do not repeat legacy activation or
switch to `disabled`. The two-stage procedure below is for an installation
whose canonical records still use disabled-compatible validity.

Do not combine binary rollout and destructive legacy expiry in one restart.

1. Pause producers and remote intake, stop the service and create one recovery
   point containing the active immutable application, complete operator
   configuration, both SQLite databases and owned recovery files. Use a
   SQLite-consistent backup or preserve matching side files; do not copy a live
   main DB while omitting its WAL. Keep completed slices needed for delivery.
2. Deploy the new binary with the existing configuration still set to
   `mode: disabled` and `existing-records: reject`. The installer preserves the
   operator file and writes the new fresh-install template as
   `application.yml.new` for review.
3. Start once and require local health `UP`. This compatibility start applies
   additive schema migrations but keeps every legacy row active.
4. Pause producers, stop the service and retain a coordinated pre-activation
   recovery point. Change the operator configuration
   to `mode: fixed`, choose a positive `fixed-ttl`, and set
   `existing-records: expire`.
5. Restart. Admission archives and removes all pre-activation rows before
   intake, stateful oneshot/export work or lifecycle scheduling opens. Empty
   active storage and header-only projections are valid outcomes.
6. Require health `UP`, inspect aggregate lifecycle counts and resume producers.
   New accepted observations repopulate the active set.

Activation is durable and one-way for that dataframe database. After activation,
changing only the configuration back to `disabled` fails startup.
If activation stops partway, keep intake paused, correct the cause and restart
with the same accepted policy. Already archived batches are not rolled back.
Use [configuration check/apply](configuration.md) for file validation and
activation; a valid file alone does not prove database admission or undo an
earlier canonical transaction.

## Health and clocks

The service uses the host system UTC clock behind an isolated lifecycle clock
boundary. Keep NTP/time synchronization healthy and never roll the clock back to
extend IOC validity.

```bash
ioc health --component lifecycle
ioc health --json
curl --noproxy '*' --silent --show-error \
  http://127.0.0.1:8081/actuator/health/lifecycle
```

The lifecycle component exposes aggregate counts only; it never returns IOC or
source identifiers. Important fields are:

| Field | Meaning |
|---|---|
| `admission` | `PENDING`, `PREPARING`, `ADMITTED` or `FAILED` canonical-data admission |
| `activation` | `DISABLED_COMPATIBLE`, `ACTIVATING` or `ACTIVE` persisted state |
| `clock` | safe, temporarily clamped or unsafe effective UTC state |
| `dueRecords` | physically present rows already past the active boundary |
| `historyRecords` | retained closed lifecycle snapshots |
| `pendingProjections` | mutable artifact projections still needing convergence |
| `dueBacklogAgeMs` | age of the oldest due row not yet archived |
| `artifacts` | aggregate stored/due/history counts per artifact |
| `latestCycle`, `lastFailureCode` | last reconciliation state and an optional retained failure code |
| `clockBackwardSkewMs`, `clockClampAgeMs` | observed backward skew and clamp age |

The CLI queries the running daemon, not a local database. It returns `0` for
`UP`, `1` for a non-`UP` health result (including `DEGRADED`) and `2` for an
unreachable endpoint or unusable response. The curl example preserves a
degraded/down JSON response for inspection; curl success alone is not health
acceptance. Match host/port to the deployment if they differ from the defaults.

`DEGRADED` may reflect admission still preparing, a short recoverable clamp,
pending projections, an old due backlog or an unfinished/failed reconcile cycle.
Require `UP` before resuming intake after a cutover.
`DOWN` after a clock rollback is fail-closed: correct the host clock and restart
only after UTC is trustworthy. Do not edit lifecycle timestamps or control rows.

## Retention and capacity

Expiration and retention are separate. Expiration stops active use immediately;
history retention later removes audit snapshots in indexed bounded batches.
Complete receipts permit compatible confirmation replay without repeating ETL;
they expire under a separate retention contract. They are not an unlimited
archive or a substitute for backup. Deleting history never resets lifecycle or
canonical-ID allocators.

Export slots follow a different rule. Expiration and history retention do not
renumber survivors. At the next otherwise eligible export, vanished lifecycles
release their slots and new lifecycles take the smallest free positive slots;
the remaining slots come from a durable high-water mark. Gaps are valid until
new rows consume them, and completed historical slices are never rewritten.

The shipped runtime defaults are:

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

Lifecycle activation and schema migration are separate rollback boundaries.
Even before TTL activation, a compatibility start may migrate a DB beyond the
old binary's supported format. Additive migration does not prove backward
compatibility: the migrator rejects a newer `user_version`.

Use binary/configuration-only rollback only when that exact combination has
been qualified against both current schemas. Otherwise stop the service and
restore the matching pre-upgrade recovery point. Reversing lifecycle activation
requires a coordinated pre-activation point with a compatible binary,
configuration, **both** databases and owned recovery files. Never lower
`user_version`, delete control rows or restore only one DB/YAML.

Mutable CSV can be regenerated through the projection owner. Historical
immutable slices need their original verified files; exporting current rows
does not recreate an old snapshot or delivery identity. Already delivered
remote files and sources moved after the backup require separate reconciliation.

## Test on a disposable host

Repository-local validation needs no root privileges.

The current smoke/load harness has a known terminal-filename mismatch
([TOOL-10](../KNOWN-ISSUES.md)): it waits for a legacy done name while Native
archives a sealed source. The command below is the intended scenario, but its
timeout is not evidence of failed TTL and is not a completed qualification.
Until the harness is repaired and rerun, use focused lifecycle/projection/export
tests for their stated contracts and retain the missing end-to-end evidence.

```bash
make lifecycle-smoke
```

The intended scenario sends data through normal daemon ingestion and checks
active-to-history movement, bounded retention, projection convergence and
non-reused internal identities. A successful run writes its
environment/query-plan/throughput report under `.dev/`.
Focused export tests separately verify stable/reusable slots and
immutable historical mappings. For the separate, expensive 100k canonical-row
reference profile, run `make lifecycle-load`; its load/RSS results are specific
to the recorded environment, not guaranteed by the smoke run. A final release
candidate still needs the packaging fresh-install/upgrade/rollback test on a
disposable systemd host.

See also the [deployment guide](deployment.md),
[configuration reference](configuration.md) and
[daemon runbook](daemon-operations.md).
