# CAP-7C second corrective cycle

Status: implementation and qualification in progress. C5 and G6 remain
NOT_ACCEPTED; the default native runtime and proposed ADR 0041 are unchanged.

## Baseline and boundaries

This cycle follows the retained [first corrective qualification](cap-7c-c5-corrective-qualification.md).
Its original evidence commit identities remain unchanged in historical reports.
The working history was reworded by the owner; the tree at
`6a41a787212389cd10479b35b87f3a4b2c1cdbf5` equals the original
`ddcd92d145fb7c6968878bbd0b0741030ae45067` tree.

Keep standard row/field, aggregate memory/disk, CPU/cgroup, cleanup and safe-clock
limits. Do not add retries, native fallback, PostgreSQL or production activation.
Fresh publication UTC remains lifecycle confirmation authority.

## Work sequence

1. Fix a reproduced stale-clock sample: acquire ownership/read high-water before
   sampling UTC. Cover write ownership and read-only connection acquisition;
   retain actual rollback, clamp-duration and restart regressions.
2. Instrument per-file/category WAL and adjacent UTC/monotonic control timings.
   Exclude collector-failed observations from completed-window medians while
   retaining failed samples. Resolve ownership/lease and transfer amplification
   only from confirmed source or measurement evidence.
3. Freeze the exact committed runtime/helpers and repeat standard admission,
   DOCX 100k, processed-collapse 100k and aggregate 100k comparisons. Retain
   failures; only fully collected windows with independent oracles qualify
   throughput, allocations and paired semantics.
4. Run final verify, PMD, resource-ownership watchlist and documentation gates;
   review findings and keep qualification distinct from acceptance.

## Confirmed clock defect

`JdbcLifecycleClock.now(Connection)` sampled wall UTC before the SQLite write
ownership update and high-water read. `inspect()` sampled before connection
acquisition and high-water read. Deterministic JDBC hooks advancing UTC and
durable high-water by three seconds reproduce false unsafe status with no
backward wall-clock movement. The fix samples after these blocking boundaries;
it does not change skew/clamp policy. This establishes a code defect, not the
exclusive cause of the previously retained load failures.

## Collection corrections

Raw resource samples now partition named WAL bytes into canonical, service,
canonical-private, document-private and other categories and retain each path.
Peaks retain category totals, not an unbounded history of workspace identities.
These totals exclude unlinked SQLite TEMP files. UTC and monotonic timestamps
are adjacent observations, not an assertion that host UTC never stepped.

Qualification control/cleanup responses expose UTC before/after, monotonic
timings and the actual safe-clock snapshot. HTTP failures retain bounded
diagnostic context and reject the entire collection window. Collector-failed
observations remain in raw reports but no longer count as completed windows or
contribute to series medians; fully collected screen failures still retain
their measured intervals. Offline contracts cover storage partitioning,
symlink exclusion, HTTP failure propagation and failed-window accounting.

## Workspace isolation correction

Configured canonical headroom previously limited document concurrency but let
two control/export attempts occupy both canonical working sets. Promotion could
then fail admission while its document already held a lease. The corrected
factory permits only one control workspace after headroom configuration. The
existing publication unit supplies the promotion/control distinction. A fair,
interruptible control-preparation lock queues normal callers before workspace
or writer ownership; the factory retains a nonblocking safety check. Recursive
calls or calls already owning the canonical writer are rejected. No new
orchestrator, blocking nested resource wait or larger budget is introduced. Deterministic
regressions retain a document and control workspace, reject a second control,
admit promotion, and verify that failed pins and normal disposal release the
reservation. Existing bounded reconcile/recovery remains the control backstop.

## Confirmed export reader amplification

The clock-only diagnostic fork exposed control-workspace contention with a
SAFE clock snapshot. Collection was already incomplete. Per-file samples also
localized the large WAL to canonical storage, not the private native database.
A single retained JVM thread dump places the export worker in
`JdbcExportSlotRegistry.requireCompleteAssignments` while promotion appends
hidden chunks. An actual-schema `EXPLAIN QUERY PLAN` reports assignment-view
materialization followed by `SCAN a LEFT-JOIN` for each active row. The equivalent
missing-assignment probe uses a full profile/artifact/lifecycle index lookup.

Replace the count/outer join with `NOT EXISTS ... LIMIT 1` in the same snapshot.
Slot-key uniqueness and committed visibility remain unchanged. This removes a
confirmed repeated scan and shortens a reader capable of delaying checkpoint
progress; it does not claim to eliminate every WAL or transfer bottleneck.

The diagnostic fork was intentionally interrupted after establishing the seam
and its collector failure; its partial staged observations are not throughput
or before/after acceptance evidence. Preserve the raw failed/aborted report,
thread dump and query plans alongside the completed native failure. New C5
forks must use committed corrected sources without these extra diagnostics.
