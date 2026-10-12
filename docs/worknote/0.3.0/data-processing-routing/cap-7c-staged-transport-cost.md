# CAP-7C bounded staged-transport optimization and C5 repeat

Status: the owner-authorized bounded cycle is complete on 2026-10-11.
Retain the chunk-local statement-reuse optimization. Ten whole-service windows
complete collection and independent output checks; the previously incomplete
large staged DOCX and aggregate-import cells now have valid measurements.
**C5/G6 remain NOT_ACCEPTED.** Native remains the default, and proposed ADR 0041
is not activated. This is not a replacement workload or acceptance budget.

The `qualification/capacity/cap-7c-staged-transport-cost.json`
preserves all reports, metric scopes, bytecode identities, negative screens,
clock observations, raw-file hashes and cleanup results. It supplements the
[second corrective C5 repeat](cap-7c-c5-second-corrective-qualification.md) and
implements one candidate from the [feasibility review](capacity-feasibility-review.md).

## Change and ownership

`JdbcTypedPublicationChunk` previously constructed SQL and prepared/closed an
INSERT for every typed version row. It now reuses the existing
`JdbcStatementScope` within one chunk and its caller-owned connection and
transaction. Contiguous table families share SQL construction and a statement
lease; returning to a family in the same chunk reuses its cached statement.
The existing 32-statement bound, binding reset and exception-safe close apply.

The scope closes before the caller commits. It never closes the connection,
shares state across threads/transactions, reorders effects or retains a second
payload buffer. Chunk admission remains at most 256 rows/1 MiB. Values, NULL,
BLOB, tombstones, deadline flags, attempt identity, digest, journal replay and
atomic committed visibility are unchanged. Both runtime append and drained
migration copying use the same insertion owner.

Regressions verify interleaved families with two prepared version statements,
all statement closures, exact replay without additional versions or INSERTs,
invisible pending rows, null/blob and literal/anchored deadline values. An
injected second-row failure plus a statement-close failure preserves the
original exception and its suppressed close error, closes resources and rolls
back versions, journal and facts. The focused protocol integration suite passes
54 invocations; the full native/staged TCK and fault suites pass in `make verify`.

## Clock diagnosis and observed stabilization

Before the user stopped guest NTP, an independent 90.03-second probe recorded
three relative UTC steps around -2.48 s, about 32 seconds apart. Read-only host
inspection found WSL2, Hyper-V implicit time synchronization enabled, and an
active `systemd-timesyncd` with a 32-second poll and -2.478823-second offset.
This is strong evidence for competing host/guest synchronization, rather than
a reason to relax the application's safe-clock policy.

[Ubuntu's WSL time-synchronization documentation](https://ubuntu.com/wsl/docs/stable/explanation/time-sync/)
describes this conflict and guest-NTP configuration when using implicit Hyper-V
synchronization. The user stopped `systemd-timesyncd`; the agent made no OS
service or persistent configuration change. The service remains user-stopped.
The observed mechanism is a diagnosis for this host, not proof of the cause of
every historical clock failure.

Four subsequent independent 90-second preflights, including one before each
C5 series, detected no backward step exceeding 100 ms. Adjacent raw resource
samples in all ten completed service lifetimes likewise contain no relative
backward step below -100 ms, and no runtime clock collector fails. This proves
stability only within the retained observation windows.

The new C5 preflight brackets UTC reads with monotonic timestamps, retains the
raw samples and SHA-256, and returns `BLOCKED_CLOCK` before any JVM if it detects
a step. Its threshold is a diagnostic sensor, not a changed runtime tolerance.
Runtime safe UTC, publication-time expiry/equality and fail-closed rejection
remain authoritative. Tools regressions cover backward rejection and a delayed
bracketed read without a false step.

## Measurement identities and method

| Identity | Frozen value |
|---|---|
| Old executable source | `6f7541e5603b73e3806ee1ae2ff5f88831260455` |
| Old JAR SHA-256 | `470a9f980fd622c0171fd6788f3945c1797ebb2d7483db009a76d75e5a01b16b` |
| Candidate and complete C5 source | `e2a8196a5f8c0950597995c88cc6d744149deba2` |
| Candidate JAR SHA-256 | `9108721b59052fc3c0c2d8ec685f9730a0c565a047bc6b1e31df5e991fe75010` |
| Micro-probe source SHA-256 | `0bc7a04d21c16dad37c46601c2ab9333787f7e029915db833eef26f2d9af3055` |

An independent comparison of all 1,482 IOC production class entries in both
executables finds differences only in `JdbcTypedPublicationChunk` and its nested
`Row` record. This is one changed production compilation unit; class hashes
include source/debug metadata. Micro reports' `source_commit` identifies the
checked-out harness, not necessarily the old executable's source. The bundle
records both explicitly, including the late old-JAR control.

All service forks use one clean source HEAD, frozen JAR, unchanged helper
classes and standard policy. The private envelope remains two CPU equivalents,
Xms/Xmx 128/512 MiB, cgroup high/max 768 MiB/1 GiB, standard 256 KiB rows,
64 KiB fields, aggregate preparation admission 64 MiB, per-workspace disk 2 GiB,
aggregate disk admission 8 GiB and canonical private cache reservation 512 KiB.
`--bounded-row-profile` is false. Actual effective policies and runtime limits
are retained by identity. Normal service cleanup and the real one-second
control probe run; extra retention/version cleanup executes after every fifth
successful probe. No build, profiler or query experiment runs concurrently
with primary windows. This is a shared WSL qualification host, not a dedicated
production performance environment.

The C5 driver alternates backend order and completes both members of each
pair. It stops later pairs after a negative member screen. Consequently DOCX
and aggregate retain one complete pair each, not three-fork medians; processed
import completes all three pairs. No automatic retry is used. Historical
targets and failed results remain unchanged.

## Isolated typed-chunk comparison

`make sqlite-typed-chunk-cost` compiles a tools-owned probe against frozen real
runtime classes. Each of three fresh JVMs per cohort transfers 200 chunks of
256 rows (51,200 rows), journals and commits them through the actual typed
protocol, checks every mixed NULL/text/BLOB value and final receipt, and removes
private state. A fixed late old-JAR cohort checks drift after the candidate.
It uses identical probe bytes, flags and FULL SQLite commit policy.

This is a mechanism diagnostic: allocations belong to the calling thread;
Spring, hydration, output, concurrent controls, heap/RSS and full C5 admission
are outside its scope. The clock is fixed test UTC; durations are monotonic.

| Metric, median of three forks | Old JAR, early | Candidate | Old JAR, late control |
|---|---:|---:|---:|
| Complete chunk loop, s | 9.677 | 8.632 | 9.144 |
| Chunk preparation, s | 0.073 | 0.079 | 0.076 |
| Journal + typed append, s | 4.162 | 3.469 | 4.021 |
| Commit, s | 5.408 | 5.083 | 5.003 |
| Exact caller allocations, MiB | 623.80 | 216.71 | 625.30 |

Caller allocations fall **65.26%** against the early cohort and **65.34%**
against the late control. Append medians fall 16.64% and 13.72%. Commit is not
optimized: raw commit durations span 3.01–16.01 s before, 3.92–14.01 s after,
and 4.11–5.19 s in the late control. The total-loop medians are observations,
not a stable whole-service speedup or a prediction for large deliveries.
All nine forks pass exact values/receipt checks and remove temporary state.

The initial probe prototype failed compilation because direct `javac.Main`
did not expand a classpath wildcard. The helper now explicitly expands only
the compiler's library paths. That failed prototype is retained separately
and contributes no baseline, timing or successful sample.

## Complete large-cell C5 results

Each cell below is one completed native/staged pair with the same physical
input and independent final fields, complete keys, canonical IDs, source
provenance, mutable projections, immutable slices and reusable slots. All five
artifacts and three enabled profiles are checked. Times are conservative local
handoff-to-completed-slices intervals after subtracting declared intake quiet
time; they are not exact dispatch-eligibility latency. Throughput counts input
occurrences/rows, not final winners. Heap/RSS and file sizes are sampled peaks;
allocations are exact whole-JVM window bytes.

| Metric | DOCX 100k unique, native | DOCX 100k unique, staged | AS_IS aggregate CSV 100k, native | AS_IS aggregate CSV 100k, staged |
|---|---:|---:|---:|---:|
| Complete local window, s | 68.127 | 334.567 | 28.089 | 135.349 |
| Input occurrences/rows per s | 1,467.8 | 298.9 | 3,560.1 | 738.8 |
| Exact whole-JVM allocations, GiB | 11.540 | 30.567 | 4.250 | 10.374 |
| Sampled heap peak, MiB | 89.37 | 93.74 | 86.13 | 89.39 |
| Sampled RSS peak, MiB | 376.24 | 408.53 | 351.45 | 379.36 |
| Sampled swap peak, MiB | 0 | 124.31 | 0 | 3.64 |
| Maximum physical writer hold, s | 13.075923 | 0.952759 | 16.611018 | 0.203733 |
| Maximum control writer wait, s | 12.216399 | 0.095500 | 16.204109 | 0.079170 |
| Maximum eligible control completion, s | 12.238843 | 0.100952 | 16.220801 | 0.081002 |
| Maximum scheduled control lateness, s | 0.000422 | 13.825905 | 0.000199 | 2.501571 |
| Maximum extra maintenance completion, s | 0.531725 | 14.795362 | 0.001803 | 3.482943 |
| Sampled named workspace peak, MiB | 144.40 | 516.65 | 234.73 | 436.14 |
| Sampled canonical WAL peak, MiB | 190.59 | 57.07 | 134.42 | 38.99 |
| Sampled canonical-private WAL peak, MiB | 0 | 199.00 | 0 | 141.28 |
| Process CPU during reference window, s | 58.91 | 257.33 | 27.52 | 103.68 |

Staged/native time ratios are 4.911 and 4.819; allocation ratios are 2.649 and
2.441. Both comparisons pass independent semantic equivalence. Native DOCX
fails local-time/hold/control screens; native aggregate fails hold/control.
Both staged cells fail the unchanged local-time screen. All four complete
collection, output verification and cleanup, with no collector ERROR.

The document's measured PROMOTION admissions are five native operations with
24.62 s cumulative physical hold, versus 4,185 staged operations with 227.60 s
cumulative hold. Aggregate has one native operation/16.61 s versus 1,566
staged operations/93.76 s. These include the full measured canonical-bean
operation class, not just payload INSERTs. Smaller maximum holds improve
opportunities for competing control writes; they do not remove the aggregate
transaction, version, journal, preparation and publication cost.

Short holds also do not prove timely scheduling of the whole maintenance lane.
The collector executes extra maintenance synchronously before its next probe:
up to 14.80 s of maintenance accounts for roughly 13.83 s scheduled lateness
in the document cell. Preserve both eligible completion and cadence metrics.
Staged document cgroup memory-high events and 124.31 MiB swap reveal pressure
despite RSS being below its screen. No OOM or forced resource increase occurs.

The document's aggregate publication-phase counter records one REBUILD and
maximum attempt 2; aggregate records none and maximum attempt 1. The counter
covers workload and control/cleanup units together and does not identify which
unit rebuilt or why. It proves observed rebuild work, not a correctness defect,
a main-delivery retry or its contribution to elapsed time. All final oracles
still pass. A future reason/unit diagnostic belongs in a separate run.

Historical staged DOCX 421.54 s and aggregate 153.77 s were collector-error
windows without the complete prescribed load or oracles. They are not valid
before-times for this candidate. The old/new controlled benefit above is the
typed-chunk diagnostic; this C5 repeat establishes complete current service
cost and native/staged tradeoffs, not an isolated end-to-end before/after gain.
Named file maxima exclude unlinked SQLite TEMP and are not total physical-disk
high-water or cumulative bytes written. Peaks need not occur simultaneously.

## Processed host/COALESCE import: three complete pairs

The physical CSV contains 100,000 distinct detailed URLs converging on 20
hosts through the active selected host view and COALESCE contract. Real Spring
binding/preflight, preparation, extraction/classification, route runtime,
private SQLite, canonical commit and export components execute in the isolated
test backend composition. Each fork checks receipt `COMMITTED` independently
from delivery `TERMINAL/SUCCEEDED`, 100,000 source rows, 20 accepted stage rows,
zero rejected rows and 20 public mutations. There is no delivery retry. Final
masks contain 30 rows including the ten warmup rows. Provenance oracles and
the committed-family census find no orphan origins.

| Metric | Native | Staged |
|---|---:|---:|
| Raw complete local windows, s | 16.668 / 16.695 / 16.589 | 16.698 / 16.819 / 16.546 |
| Median complete local window, s | 16.668 | 16.698 |
| Median input rows per s | 5,999.5 | 5,988.9 |
| Whole-JVM allocations, GiB, range | 3.054–3.131 | 3.280–3.315 |
| Sampled heap peak, MiB, range | 68.36–83.69 | 76.17–88.14 |
| Sampled RSS peak, MiB, range | 348.22–351.13 | 368.29–373.82 |
| Maximum writer hold across forks, s | 0.089873 | 0.019599 |
| Maximum control writer wait across forks, s | 0.033090 | 0.000084 |
| Maximum eligible control completion across forks, s | 0.033293 | 0.000550 |
| Maximum extra maintenance completion across forks, s | 0.002330 | 0.934498 |
| Sampled swap across forks, MiB | 0 | 0 |

All six individual and both backend series screens pass. Paired time ratios
are 1.002/1.007/0.997, allocation ratios 1.080/1.059/1.053. These small timing
differences do not establish an end-to-end speedup. Staged adds modest overhead
when the final publication is only 20 keys; input size alone does not predict
the cost of large unique canonical results. The three staged windows have no
REBUILD and maximum attempt 1. This cell does not establish populated-base,
sustainable mixed-arrival or general import capacity.

## Gates, retained disposition and next decision

At measured source HEAD, `make verify`, `make pmd-analysis`,
`make pmd-watchlist` and `make lint-shell` pass. The tools suite passes 36 tests.
The first verify attempt correctly rejects one moved SpotBugs identity; it is
not discarded. Review proves the same catalog-owned, quoted identifiers and
bound business values as before. Only exact finding `CAP-7C-C3-SB-025` moves
from `insert` to `insertRows`, with updated hash/signature and explicit rationale.
No additional finding, scope exclusion or tolerance is accepted. The repeated
gate has 168 raw reviewed findings and zero visible findings. CPD stays 23/23,
adopted PMD 20 findings, and watchlist 80; none adds a finding in the changed
chunk class. Coverage/report/architecture and per-scope ratchets pass.
Final evidence-commit verify/PMD freshness is checked separately.

All nine completed micro JVMs and ten service JVMs terminate; private business
state and frozen runtime copies are removed. Four service forks retain negative
screens and six screen-pass. C5 stops further large-cell pairs after the first
failed pair, as prescribed. There is no million-row, populated-state, live SMB,
full import/format matrix or complete physical-disk qualification here.

The retained improvement is narrower than staged activation: remove a verified
per-row statement cost while preserving the established publication protocol.
Staged now demonstrates short physical holds on complete large deliveries,
at materially higher total time, CPU, allocations and temporary storage.
Control cadence and maintenance cost remain part of the availability decision.
The finite cycle ends here, with no new corrective loop implied by an old
screen failure. Selecting staged for release still requires an explicit
workload/availability envelope and the remaining C5/C6 operational evidence,
including startup/deployment ownership, complete backup/restore boundaries and
safe promotion-pin compaction. Native remains the production default.

Reproduction uses the existing facades and a new private output directory:

```bash
make sqlite-typed-chunk-cost CHUNK_COST_ARGS='--jar <frozen-jar> --output .dev/<new-root> --chunks 200 --samples 3'
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar <frozen-jar> --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/<new-root> --rows 100000 --samples 3 --format docx --shape unique --maintenance-every 5'
```

For AS_IS aggregate use `--shape unique --import-artifact ioc_aggregate`.
For processed host import use `--shape collapse --import-artifact masks
--processed-import`. Keep all failed screens, stop unstable collection, and
do not run a build or profiler concurrently with primary measurements.
