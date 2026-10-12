# Bounded immutable receipt-payload experiment

Date: 2026-10-12. The requested cycle is implemented and measured: attribute the
remaining receipt cost, try one physical representation if justified, and check
reads, recovery and cleanup. Resource acceptance and production activation remain
open. This is an isolated JDBC candidate, not a new production backend or lane.

## Result and disposition

The experiment reduces ordinary receipt storage and retirement work. It does
**not** materially speed up the tested whole-service document/import windows,
and complete ordinary receipt reads become slower. Retain the implementation and
raw evidence as an experimental option; do not activate the layout on these
results. Native storage remains the application default. Do not start another
optimization cycle automatically.

Both compared variants already use direct projection controls and the compact
chunk journal. Consequently their difference measures this receipt change, not
the earlier ACK/journal improvements or native-versus-staged performance.

## Attribution before implementation

The corrected baseline-only census is retained in
`qualification/capacity/receipt-payload/attribution/report.json`.
Its document has 116,740 retained typed rows. Ordinary artifact receipt rows and
their field positions account for 32,131 rows and 25,702,400 bytes of table/index
pages out of 92,688,384 bytes across the typed families: approximately 27.7%.

This establishes a material **storage** opportunity. It does not attribute
27.7% of elapsed time or allocations to receipts. Retirement also has a concrete
fan-out mechanism: the previous path hydrates child payloads, publishes their
tombstones and later reclaims both versions. The finite retirement probe below
measures that mechanism separately.

Page attribution uses the pinned SQLite JDBC driver's read-only `dbstat` and
records the actual query plans. `dbstat` describes B-tree pages and omits freelist
and other non-B-tree pages; it is not the total DB-file size or lifetime write
volume. See [SQLite DBSTAT](https://www.sqlite.org/dbstat.html).

## Physical change and preserved ownership

`JdbcReceiptPayloadLayout.IMMUTABLE` applies only to admitted ordinary
`<artifact>_receipt_rows` and `confirmation_receipt_field_position`. Canonical
rows, provenance, aliases, receipt headers/artifact markers and managed-import
result children retain their existing representation. The persisted layout
becomes part of the configuration pins and is checked on reopen; older candidate
databases without that capability retain VERSIONED behavior. There is no public
configuration switch or populated conversion to the new layout.

The selected families keep their typed business columns and five publication
metadata columns instead of eight. They retain attempt/key/parent indexes while
omitting the general latest-address machinery and redundant copied lookup
indexes. Committed unit/fence, the logical receipt parent, its retirement barrier
and the artifact marker jointly determine visibility. A STAGING parent still
admits payload from already committed artifacts; COMPLETE alone cannot gate it.

Native key identity includes ordinal/field, not merely IOC `row_key`. A visible
key cannot be inserted twice. An attempted payload update or individual child
deletion under a live parent fails closed. Both transfer passes use the same
filter and count the physical effects actually published. Parent retirement
publishes the root/artifact markers; it omits child tombstones and leaves bounded
physical cleanup to reclaim the hidden payload. A committed retirement barrier
cannot be removed while an older child could be resurrected by reuse of the
receipt ID.

This representation uses the existing publication protocol, epoch validation,
fencing, transaction/admission owners, publication UTC and receipt-first
recovery. It does not introduce independent commit authority. Native and the
previous staged layout remain unchanged by default.

## Reproducible whole-service comparison

Command:

```bash
make receipt-payload-comparison RECEIPT_COMPARISON_ARGS='--baseline-jar .dev/receipt-payload-baseline/ioc-app.jar --baseline-helpers .dev/receipt-payload-baseline/helpers --jar bootstrap/ioc-app/target/ioc-app-0.3.0-SNAPSHOT.jar --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/receipt-payload-primary --rows 10000 --samples 3'
```

The baseline keeps its original composition helpers. Both variants use the same
current instrumentation, direct ACK, chunk journal and production Spring
components. Each fork owns temporary service/dataframe DBs, workspaces, physical
inputs, local projections and immutable slices. Three pairs per profile alternate
execution order: 12 JVM forks, six pairs, all COMPLETE/CORRECTNESS_PASS.

Measurement identities:

- HEAD: `46cb4ab3b1347c3fa713e9748e2f28c63bcacac3` plus the recorded dirty tree;
  source SHA-256: `e332a3341ea1dad5676f527c34d555ae015b5776f4c70f7bb2a4b4a96dc652b4`.
- Baseline JAR: `d86ee8f6971c345150071cb407ba14063a55a80173c430126494ea47f42e94de`.
- Candidate JAR: `02070672c602552d8f145f00f96c81ae23839ef8e4563f475792b17bf318e40a`.
- Policy SHA-256: `47ee12d02f641bf7bf98eb1c9745bfd87a1c2dea97e69b0922f6f3629be12692`.
- OpenJDK 21.0.12.1; `-Xms128m -Xmx512m`; systemd cgroup CPU quota two CPUs,
  memory high 768 MiB, memory max 1 GiB. Effective flags/limits, frozen class
  digests, clock samples and source identity are retained in the raw report.
- The 90-second UTC preflight passed with 899 samples. There were no OOM events
  or unsafe-clock failures in these windows. This was not an exclusive machine.

After the frozen series, persisted-format discovery moved its unchanged default
return outside the JDBC resource block, and deterministic negative/retirement
tests were strengthened. Physical layout, visibility predicates and bulk/read/
retirement algorithms did not change. Performance numbers belong to the exact
frozen binaries above; final source correctness is checked by the build gates.

Profiles: 10,000 mixed-type unique IOC occurrences in DOCX with 40 sections, and
10,000 physical processed-import CSV rows using the active host/COALESCE route.
Each fork starts with the same 100-row seed and control fixture. The independent
oracle checks final public fields, full keys, IDs, lifecycle IDs, aliases,
provenance/positions, receipts, mutable projections and immutable exports.
Paired real-port receipt reads separately verify equal ordered payload digests
and row counts. The import commit receipt and its result children are included
in the independent canonical oracle but **not changed by this experiment**.

All six document forks had one rebuild, 462 HIDDEN_CHUNK phases and 116,802
committed effects. This result is not explained by avoiding a retry in only one
variant. The slower 9.686-second baseline import fork is retained.

## Primary measurements

Values are medians of three forks per variant, not confidence intervals. MiB
means 1,048,576 bytes. VERSIONED and IMMUTABLE both denote staged variants.

| Metric | Document VERSIONED | Document IMMUTABLE | Import VERSIONED | Import IMMUTABLE |
|---|---:|---:|---:|---:|
| Local completion, seconds | 18.564 | 18.572 | 7.650 | 7.610 |
| Allocations during bulk window, MiB | 3,300.593 | 3,293.579 | 414.980 | 414.425 |
| CPU during the observed fork, seconds | 66.360 | 68.130 | 49.210 | 48.980 |
| Sampled fork heap peak, MiB | 88.225 | 89.621 | 88.389 | 87.846 |
| Sampled fork RSS peak, MiB | 395.828 | 393.211 | 382.145 | 382.367 |
| Dataframe DB after fixed cleanup, MiB | 89.305 | 83.320 | 2.859 | 2.750 |
| Target payload table/index pages, MiB | 24.527 | 18.551 | 0.449 | 0.336 |
| Sampled fork named-file WAL peak, MiB | 44.301 | 49.622 | 11.127 | 11.049 |
| Sampled fork workspace peak, MiB | 56.880 | 56.880 | 12.739 | 12.739 |
| Canonical WAL after fixed cleanup, MiB | 8.000 | 8.000 | 4.287 | 4.114 |
| Promotion writer holds, total seconds | 3.132 | 2.961 | 0.002 | 0.002 |
| Maximum promotion writer hold, ms | 33.071 | 31.968 | 1.377 | 1.503 |
| Projection metadata read p95, ms | 0.075 | 0.078 | 0.072 | 0.085 |
| Complete ordinary receipt scan p95, ms | 197.978 | 312.530 | 9.274 | 16.385 |
| Complete ordinary receipt scan p99, ms | 216.038 | 330.923 | 12.654 | 18.878 |
| Caller allocations per receipt scan, MiB | 110.574 | 110.574 | 1.815 | 1.815 |

Local completion is the upper sampled producer handoff-to-ready-slices window
minus the configured two-second stability quiet period. Bulk allocations and
writer metrics stop at the bulk window's end. CPU, heap/RSS and storage peaks
cover the **whole observed fork**, including startup/seed, bulk and the subsequent
100 metadata reads, 96 maintenance calls and 20 complete receipt scans. They are
not pure bulk costs. Heap is sampled approximately once/second; RSS at 100 ms.
Named-file storage sampling excludes unlinked SQLite TEMP files.

Receipt scans include real lookup/count validation, cursor traversal, per-row
field-position reads and digest construction: 21,987 rows for the document,
320 ordinary receipt rows from the initial fixtures for the import. The import
figures are not a scan of the managed-import result receipt. Each fork has only
20 scan samples; nearest-rank p99 is the sample maximum, not production p99.

The document saves 24.4% of targeted page space and 6.7% of the dataframe file,
but its elapsed time and allocations barely change. Its complete receipt p95
increases 57.9%. Additional parent/marker/retirement visibility probes are a
plausible explanation supported by the retained plans; their individual runtime
contribution was not isolated. Read allocations stay essentially equal because
the reconstructed logical rows and digest work are equal.

The complete control request maxima also need qualification: document VERSIONED
forks recorded 14.1, 9.5 and 467.0 ms; IMMUTABLE recorded 52.3, 507.5 and
599.3 ms. These are not writer-hold durations. CONTROL queue waits remained at
most 22 ms and holds at most 10 ms. Tail probes had sub-millisecond initial reads
and no applied ACK/failure mutation. `recordFailure`, even for the pending-only
probe, admits the common control-preparation mutex also used by private-prepared
maintenance. Blocking there is consistent with the evidence, but there is no
per-mutex timing attribution. Do not infer a 36-fold backend regression from
the medians of three maxima, or claim improved complete control latency.

## Finite retention, pinned readers and catch-up

The separate quiet diagnostic uses the real file-backed adapter ports, not the
Spring service throughput window:

```bash
make receipt-retention-qualification RECEIPT_RETENTION_ARGS='--output .dev/receipt-retention-quiet --rows 2000 --cycles 3'
```

Each layout runs one JVM with three cycles. A cycle creates an ordinary receipt
of 2,000 rows and 2,000 source positions, pins a reader, retires the parent, then
publishes another small document before physical reclamation. Every cleanup page
scans at most 128 rows. The old cursor must still read all 2,000 rows. This tests
interleaving and finite debt convergence; it is not sustained-arrival-rate or
scheduled whole-service retention acceptance.

The fixture keeps its canonical clock fixed and advances the explicit receipt
retention cutoff. Canonical rows remain valid at that fixed clock: this isolates
receipt reclamation from lifecycle expiry. It does not establish the combined
30-day receipt-retention/12-hour lifecycle schedule under a real advancing clock.

| Metric | VERSIONED | IMMUTABLE | Scope |
|---|---:|---:|---|
| Parent retirement, ms | 272.334 | 73.615 | Median of three cycles within one fork |
| Retirement caller allocations, MiB | 50.680 | 4.690 | Same retirement call window; not all JVM threads |
| Physical retirement effects | 4,002 | 2 | Per 2,000-row receipt, excludes the following publication |
| Bounded cleanup, ms | 90.738 | 40.634 | Median; includes root/artifact and payload pages |
| Cleanup pages per cycle | 70 | 38 | Same scanned-row ceiling of 128 |
| Removed physical rows per cycle | 8,004 | 4,004 | Old payload plus required parent/version reclamation |
| WAL while the old reader remains open, MiB | 48.898 | 8.872 | Median after PASSIVE checkpoint |
| WAL after reader close/TRUNCATE | 0 | 0 | All cycles |
| Dataframe file after cycle three, MiB | 18.980 | 16.691 | 6,003 still-active canonical rows |
| Final catch-up after input stops, ms | 245.475 | 216.654 | Retire three small live receipts and reclaim targeted debt |
| Final targeted payload debt | 0 | 0 | Active canonical/provenance rows remain 6,003 |

Parent retirement becomes 73% faster in this finite probe; caller allocations
fall approximately 91%. The immutable format avoids copying/deleting all payload
children during logical retirement and avoids a second set of tombstones during
cleanup. Pinned reads survive logical retirement and reclamation. WAL still has
to retain the old reader's snapshot until it closes; see
[SQLite WAL](https://www.sqlite.org/wal.html) and
[SQLite isolation](https://www.sqlite.org/isolation.html).

## Recovery and deterministic regressions

`ImmutableReceiptPayloadIT` executes 14 cases. It covers both layouts and
multi-artifact STAGING-to-COMPLETE receipts, ordered positions and SQL NULL,
monotonic ID reservations, exact replay, key/ordinal distinctions, rejected
update/child deletion, hydrated child-retirement filtering, bounded cleanup,
receipt ID reuse before/after reclamation, persisted pins and malformed formats.
Fault-injected JDBC close failures preserve the primary error, suppress secondary
close failures and close both owned resources.

Real child JVMs are forcibly terminated at HIDDEN_CHUNK, SEALED,
PUBLICATION_STAGED and COMMITTED. Reopen discovers the persisted layout. The
COMMITTED case deletes the private workspace and uses a RowSource which throws
if opened: recovery reads the committed receipt without repeating preparation
or advancing committed effect counts. Reopen/finalize-and-read timings are
retained in the focused JUnit XML; these small test timings establish the
recovery mechanism, not a recovery SLA. The direct-ACK/hidden-publication
interleaving regression also runs against both payload layouts, retaining the
existing epoch conflict/rebuild and generation behavior.

The test suite remains Failsafe-discovered and tagged integration. Its admission
updates the reviewed suite universe to 222 fast, 83 integration (five external
shells), 300 deterministic-offline classes. Coverage floors are unchanged.

`RECEIPT-PAYLOAD-SQL`: the two new SpotBugs generated-DDL findings are individually
reviewed; two prior method identities change with the layout parameter. Physical
identifiers are validated/quoted native catalog entries; the artifact literal
is admitted and escaped; receipt IDs and payload values are bound. No blanket
SQL suppression, coverage exclusion or PMD/CPD relaxation is introduced.

Validation commands: focused `ImmutableReceiptPayloadIT`, 45 Python driver
tests, `make verify`, `make pmd-analysis`, `make pmd-watchlist`, `make docs`.
All passed on the completed implementation. Full verification discovers 300
deterministic-offline test classes; aggregate coverage is 92.38% lines and 85.89%
branches. Exact SpotBugs review retains 170 raw findings, zero visible findings;
CPD retains 23 groups, adopted PMD 20 findings and the diagnostic watchlist 80.
The changed members introduce no CPD/PMD/watchlist findings. The JDBC branch
ratchet passes without weakening its 468-missed-branch baseline. Documentation
validation reports zero errors. Failed earlier gates remain below; benchmark
success does not substitute for these checks.

## Evidence and negative dispositions

- `qualification/capacity/receipt-payload/primary/report.json`:
  original 12 forks, phase counters, raw resource/control samples, full policies,
  driver output and compressed daemon logs.
- `qualification/capacity/receipt-payload/summary.json`: exact
  per-fork metrics, medians and descriptive ratios; no acceptance threshold.
- `qualification/capacity/receipt-payload/retention/report.json`:
  finite six-cycle oracle, frozen class hashes, corrected effects/allocation scopes
  and both raw probe logs.
- Local focused recovery report `qualification/capacity/receipt-payload/immutable-receipt-focused.xml`:
  14 deterministic cases and four process-loss recovery measurements.
- `verify-passed.log.gz`, `pmd.log.txt`, `watchlist.log.txt`, `docs.log.txt` and
  `driver-tests.log.txt`: executed implementation gates. The final facade checks
  are repeated after recording this evidence so freshness covers the documented
  worktree as well as the Java implementation.
- `attribution-failed/`: missing `ReceiptPayloadDiagnostics` in the helper freezer
  caused HTTP 500. The helper list and regression fixture were corrected. Those
  failed windows are not performance evidence.
- `retention-collector-failed/`: an empty trailing Failsafe classpath component
  caused recursive freezing of the current directory. The owned process was
  interrupted and its temporary copy removed; source files were untouched.
  Empty components and recursive classpath directories now fail validation.
- `retention-initial/`: the first successful probe incorrectly included the
  following publication in `retire_effects` and began allocation timing before
  pin initialization. Retain it as superseded scope evidence; use the corrected
  quiet run above.
- `retention-build-overlap/`: its functional oracle passed, but concurrent
  `make verify` and builds invalidate quiet cost comparison. A separate
  disposition excludes its timings; the original COMPLETE report is preserved.
- `verify-first.log.txt`: the first full gate rejected a five-branch JDBC coverage
  regression and SpotBugs report/baseline integrity. Meaningful retirement and
  resource-failure regressions and exact finding review address this; no gate
  result is silently replaced.
- `verify-second.log.txt`: functional tests, exact SpotBugs and CPD passed, but
  the branch ratchet still rejected 471 missed branches against 468. Persisted
  format discovery removes duplicated compiler-generated early-return cleanup;
  the negative family-admission test verifies the explicit audited allowlist.

The comparison does not establish large-unit performance, production p95/p99,
sustained cleanup throughput, long-term DB growth, import-result-child storage
benefit, coordinated migration/restore to this new format, or satisfaction of a
customer resource budget. Those are explicit limits of this completed bounded
cycle, not an instruction to add more implementation stages.
