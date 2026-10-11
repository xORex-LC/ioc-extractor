# CAP-7C second corrective cycle

Status: implementation and mandatory repeat completed on 2026-10-11. C5 and G6 remain
NOT_ACCEPTED; the default native runtime and proposed ADR 0041 are unchanged.
See the [second C5 corrective qualification](cap-7c-c5-second-corrective-qualification.md).

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

## Development gates

The first complete JDBC run exposed a legacy low-level capacity test that
opened several canonical workspaces without distinguishing their roles. It now
marks direct promotion leases explicitly while preserving total-budget and
thread-confinement assertions. The repeated JDBC cohort passed 512 tests with
one expected external skip.

The first full verify retained a JDBC missed-branch ratchet failure (470 versus
468). Add meaningful resource regressions for retaining-close release and total
memory rejection after taking a control reservation. Floors, exclusions and
reviewed baselines are unchanged. Final verification must cover these changes.

The subsequent full verify passed coverage and retained a CPD decrease gate
(24 expected, 23 actual). Re-running PMD CPD 7.17.0 at 75 tokens on the original
source tree and comparing complete fragments confirmed exactly one removed
group and no new groups: the two binding/result blocks in
`JdbcExportSlotRegistry` at original lines 148 and 1111. The new missing-slot
query binds `asOf` before the profile/artifact parameters and tests existence
instead of reading a count. Tighten the reviewed CPD snapshot to 23; analyzer
scope, threshold and exclusions are unchanged.

The first PMD policy run also retained a new `CognitiveComplexity` finding in
canonical workspace opening (21, threshold 16). Extract admission reservation
and conditional release into cohesive resource helpers rather than expanding
the advisory baseline. Re-run the resource regressions and all gates.

## Completed qualification

All primary forks use committed source `87fe7b0dca746a4c150bdaa18d104f5435f79680`
and one frozen JAR/helper set. Eight SCREEN_PASS, two FAIL and two ERROR results
are retained. Standard admission and all three processed-import pairs pass
complete oracles. Native large DOCX/aggregate exceed writer/control budgets;
both large staged collectors reject unsafe UTC. Adjacent raw observations now
establish actual repeated UTC backsteps near 2.48 seconds in this environment.
This is separate from the fixed early-sampling code defect; genuine clock
rollback remains fail-closed. Preserve failed windows and stabilize the
environment before attempting a complete comparative acceptance result.

Source verify, PMD, ownership watchlist, tools contracts and docs pass. Coverage
floors/baselines and the accepted SpotBugs registry remain unchanged; the one
reviewed CPD reduction tightens its exact count to 23. The report records review
dispositions and the remaining final committed-HEAD gate requirement.
