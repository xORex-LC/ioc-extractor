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
