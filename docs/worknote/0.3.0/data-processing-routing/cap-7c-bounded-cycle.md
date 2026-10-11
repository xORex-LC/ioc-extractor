# CAP-7C bounded native-cost execution and results

This execution record implements the finite cycle agreed after the
[feasibility review](capacity-feasibility-review.md). Native SQLite remains the
production default; no staged activation, resource-policy relaxation or
performance acceptance is part of this experiment.

Status: the bounded implementation and measurement cycle is complete. Retain
one allocation optimization; no stable end-to-end speedup or C5/G6 acceptance
is established. The machine-readable
[evidence bundle](qualification/capacity/cap-7c-bounded-native-cost.json)
preserves every completed fork, failed screen, runtime identity and comparison.

## Measurement contract

The existing physical full-service C5 harness owns configuration, Spring
composition, DOCX/CSV fixtures, canonical/output/provenance oracles, exact
canonical admission and whole-JVM allocation accounting, control/cleanup,
cgroup resources and private-state removal. `--native-cost-cycle` runs a fixed
number of native forks. A completed old-budget `FAIL` remains a `FAIL`; collector,
oracle and cleanup failures stop the series. Series `COMPLETE` describes evidence
completeness only and never accepts C5/G6. No retry-until-pass is used.

Additional fingerprints cover canonical key/ID/lifecycle assignments, aliases,
field ranks, receipt payloads and ordinals. They intentionally exclude varying
UTC timestamps and private occurrence IDs. The existing independent oracle
continues to check final fields/keys, sources, projections and reusable slots.

Diagnostic JFR runs are separate from primary timings. The streaming
`native-processing-profile` command attributes sampled allocation weights to
the nearest IOC stack frame, including the method descriptor. These weights
are estimates, not exact allocated bytes. Execution/native stack samples are
not elapsed method times or CPU percentages; recordings include startup and
shutdown. ECS stage durations provide the measured elapsed breakdown, without
inventing exact subphase durations from stack samples.

## Initial diagnostic baseline and selected mechanism

At source `22467cddf6aeb173c7d2c522b400ca2a8ba2c2d1`, the freshly packaged
application SHA-256 was
`1cfe167d9ba00f77c668361f9defce803c281ee2ad6319f3263e5ca1b4cec393`.
The 100,000-occurrence unique DOCX diagnostic completed its independent output
checks. Its conservative local window was 112.691 s; unchanged local-time and
writer-hold screens failed. This profiled duration is not a primary comparison.
Raw evidence is retained privately at `.dev/cap-bounded-native-profile`.

`DocumentRowCodec.text(DataInputStream)` accounted for 5,145,007,024 bytes of
sampled weight, 29.00% of the whole-recording allocation weight, the largest
attributable allocation site. Encoding is a separate overload and is excluded
from that figure. The decoder copies every field's bytes, constructs a strict
UTF-8 decoder and allocates byte/character buffers on every field of every
prepared-row pass.

The single selected mechanism is field decoding. The retained implementation
reads the unchanged version-1 payload through validated slices of the bounded input,
constructs ASCII strings directly from their validated slice and uses a strict
UTF-8 decoder for non-ASCII slices. All state belongs to one decode invocation;
there is no global cache or shared mutable decoder. It must preserve missing
fields, explicit nulls, order/rank, ID metadata, exact bounds, corrupt-input
rejection, and sealed replay. Identity validation, winner selection, canonical
mutation and receipt durability remain mandatory.

## Implementation and safety

The native-only measurement mode and deterministic fingerprints have tools
regressions for incomplete collection, retained screen failures and changes to
identity/rank/receipt payload. The cohort comparator rejects different physical
inputs, policy, runtime bounds, measurement scope or deterministic outputs. A
completed negative resource screen is evidence, not an accepted workload.

Slice decoding is implemented without changing encoding or version 1. The
workspace integration suite covers sealed replay and resource ownership. Added
regressions use an independently encoded mixed ASCII/Unicode fixture (including
supplementary characters and NUL), exact field-byte boundaries, byte-for-byte
encoding, missing/null/empty cells, order/rank, independent string ownership,
overlong/surrogate/out-of-range/truncated UTF-8 and every truncated prefix of a
valid row. The whole workspace integration suite passed 66 tests. An additional
focused check passed after strengthening the independent exact-encoding oracle.
The tools regression suite passed 34 tests.

The production change is confined to `DocumentRowCodec`: it removes the
temporary per-field byte array and per-field decoder construction. One strict
decoder belongs to each row decode; non-ASCII fields use validated input slices,
while ASCII fields construct an owning string directly. No shared state, cache,
new schema, wire version or change to row selection is introduced. Replays still
validate bounds and malformed input before canonical mutation.

## Primary full-service results

Thirteen primary forks completed: three before and three after for each input,
plus one fixed late control with the old binary. Two separate JFR diagnostics
completed. There were no collection/oracle/cleanup failures in these windows;
all owned runtime and business state was removed. Builds and profilers did not
run concurrently with primary windows. Raw logs/resources/controls/JFR remain
in ignored `.dev/cap-bounded-*` directories and are referenced by SHA-256 in
the versioned bundle; no database, CSV, JAR or credential is versioned.

The primary profiles use the same physical input, production Spring assembly,
configuration/preflight, extractor/classifier/Camel runtime and native SQLite.
DOCX contains 100,000 distinct occurrences across the five artifacts. Processed
CSV contains 100,000 different URLs converging on 20 final masks hosts using the
selected host route and COALESCE. Both have the same separate 60-occurrence
warmup. The import ends with 20 accepted mutations and 99,980 coalesced inputs;
the final masks census is 30 including warmup. This is not a general import
matrix or a populated-base/multi-source capacity proof.

The common private envelope is two CPU equivalents, `-Xms128m -Xmx512m`, cgroup
`memory.high=768 MiB` and `memory.max=1 GiB`, unchanged row/cache/disk bounds and
the actual one-second control probe, with extra retention every five probes.
Observed swap is zero. This is a shared qualification host, not an exclusive
production performance environment. Local time is the conservative complete
service window; throughput counts physical input occurrences/rows, not winners.
Allocations are measured whole-JVM bytes during that window. Heap/RSS peaks are
sampled, not retained live-state sizes.

| Profile / metric | Before (three forks) | After (three forks) | Interpretation |
|---|---:|---:|---|
| DOCX local seconds, raw | 70.138 / 70.988 / 118.532 | 141.006 / 133.216 / 155.990 | Earlier/later cohorts differ substantially; see control below |
| DOCX local seconds, median | 70.988 | 141.006 | Raw ratio 1.986; not an isolated code-effect estimate |
| DOCX physical occurrences/s, median | 1,408.7 | 709.2 | Same timing limitation |
| DOCX allocations, median bytes | 15,953,998,640 | 12,418,735,800 | **22.16% lower**, about 3.29 GiB less allocation |
| DOCX sampled heap peak, median MiB | 92.60 | 91.58 | No material resident/live-state improvement established |
| DOCX RSS peak, median MiB | 380.70 | 378.62 | No material RSS improvement established |
| DOCX workspace peak, median MiB | 144.18 | 144.31 | Unchanged within this sample scope |
| DOCX canonical WAL peak, median MiB | 190.53 | 190.54 | Unchanged within this sample scope |
| Import local seconds, raw | 18.280 / 18.568 / 17.620 | 44.284 / 32.425 / 27.793 | Main import path does not use the changed decoder |
| Import local seconds, median | 18.280 | 32.425 | Raw ratio 1.774; no speedup established |
| Import physical rows/s, median | 5,470.5 | 3,084.0 | Same timing limitation |
| Import allocations, median bytes | 3,355,615,472 | 3,331,114,976 | 0.73% difference; no meaningful optimization claim |
| Import RSS peak, median MiB | 345.08 | 344.58 | Essentially unchanged |
| Import workspace peak, median MiB | 133.97 | 154.77 | Observed 15.53% increase; not hidden by cleanup or attributed to decoder |

All three cohort comparisons pass equal-input and deterministic-output checks.
They include final fields/keys, canonical IDs/lifecycles, aliases, field ranks,
occurrence ordering, receipt payload/positions, projections and reusable slots.
Variable UTC timestamps/private occurrence IDs are deliberately excluded from
cross-fork fingerprints. Each run still passes its independent output/provenance
oracle; fingerprint equality does not replace that oracle.

### Time drift and the fixed old-binary control

The later cohort was slower even in untouched source/extraction stages and the
unchanged measured import path. To distinguish a decoder regression from a
general timing change, run exactly one additional old-binary DOCX control after
the candidate cohort. It completed in **154.704 s**, with 15,919,283,256 allocated
bytes. The same old binary's earlier median was 70.988 s, while its allocations
remained within 0.22% of the earlier median. Do not discard the slow earlier
fork or repeat this control until a short result appears.

| Untouched stage | Old binary early median, seconds | Old binary late control, seconds | Candidate median, seconds |
|---|---:|---:|---:|
| Read source | 0.696 | 1.664 | 1.709 |
| Refang | 0.974 | 1.820 | 1.762 |
| Extract | 2.586 | 5.730 | 5.392 |
| Attribute | 0.373 | 0.858 | 0.782 |

The old control's maximum PROMOTION writer hold is 29.309 s; the candidate
median maximum is 29.270 s. Their maximum control waits are 28.970/28.247 s.
This optimization therefore does not resolve the large atomic writer's
availability cost. Against the late old control, candidate median time is
8.85% lower, but one control and three candidate forks cannot establish a stable
speedup or p95. The exact cause of the global timing change is not established;
neither CPU frequency nor scheduling/clock effects are asserted as proven.

Retain the change for its repeatable allocation benefit, also reproduced against
the late control (21.99% lower). Do not claim an end-to-end latency improvement
or use the early/later ratio alone to attribute the slowdown to this change.

### Diagnostic corroboration

| Separate JFR diagnostic | Before | After |
|---|---:|---:|
| Whole-recording sampled allocation weight, bytes | 17,740,260,248 | 14,351,031,840 |
| Field-decoding sampled allocation weight, bytes | 5,145,007,024 | 1,127,139,752 |
| Decoder share of sampled weight | 29.00% | 7.85% |
| Explicit post-GC live heap, MiB | 42.37 | 42.22 |

The decoder's sampled weight falls about 78%, consistent with removing temporary
objects. These weights are not exact per-method allocations, invocation counts
or elapsed durations; whole-window allocation measurements above are the primary
evidence. Post-GC live heap is essentially unchanged. Diagnostic times
112.691/137.662 s are retained separately and never enter primary medians.
Stage logs give exact elapsed stage boundaries; the profile does not invent a
complete precise subphase timing census for mapping, SQL and receipt capture.

## Resource screens and remaining limits

All seven primary DOCX forks preserve their original **FAIL**: local time exceeds
the 45 s individual screen, writer hold exceeds 5 s and control wait exceeds 10 s.
All six import forks pass their individual screens, but the candidate import
median of 32.425 s exceeds the historical 30 s series-median target. The
native-cost mode reports series completeness, not acceptance of that target.
The completed oracles and reduced allocations do not turn these outcomes into
C5/G6 PASS. Original targets and earlier negative evidence remain unchanged.

The bounded cycle ends here. Native remains the default; staged remains an
isolated candidate, not a required dependency of configurable URL cleanup.
There is no PostgreSQL introduction, staged activation, stand deployment,
durability reduction, disabled safety check or enlarged preparation budget.

Remaining costs should be separated before a release decision:

- Required work: processing each relevant occurrence, final identity/rank
  selection, atomic cross-artifact mutation, aliases/source metadata, durable
  receipts and complete outputs. SQLite still has one writer per database;
  row count and indexes contribute real transaction occupancy. The current
  duration is not demonstrated to be an irreducible SQLite lower bound.
- Possible future optimizations: repeated prepared-row passes, SQL/receipt
  statement and serialization work, or private reducer storage. These remain
  hypotheses until individually measured with the same integrity/recovery
  guarantees. This cycle does not begin another redesign.
- Unqualified regions: million-occurrence reducer capacity, populated databases,
  concurrent sources/control/export, complete staged and SMB/recovery capacity
  matrix. No new acceptance envelope is inferred for them.
- Operational decision: production sizes/rates are still unknown. The owner must
  separately define a supported workload and tolerable writer/control latency,
  then freeze its acceptance policy before another acceptance series. The old
  limits remain failed observations, not universal engineering promises.

## Identities, reproduction and verification

The before JAR SHA-256 above was freshly packaged at `22467cdd`; its production
classes are identical to the baseline harness source `076ca6b3`. The candidate
was packaged at `d13c3a7b`, including production commit `36672551`, with SHA-256
`e9e49a2b1cd93e2243354021187e254945da85ef509a11591467ca84ccaa5b47`.
Comparing every nested project JAR's production classes finds exactly one
different class: `DocumentRowCodec.class`. Its before/after class digests are
retained in the bundle. Build metadata changes are separate from that check.

Raw `source_commit` identifies the measuring checkout. The late control was
launched by the new checkout using the explicitly frozen **old** JAR; do not
mistake its checkout identity for candidate production bytecode. Full commands,
effective limits/flags, helper/driver hashes and raw report/log/JFR digests are
retained locally and summarized in the versioned bundle.

Use the repository facade to reproduce fixed-count series, keeping before and
after frozen executables and primary/diagnostic runs separate:

```bash
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--help'
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar .dev/cap-bounded-before.jar --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/new-before-docx --rows 100000 --format docx --shape unique --samples 3 --native-cost-cycle'
make sqlite-publication-capacity SQLITE_CAPACITY_ARGS='--jar .dev/cap-bounded-before.jar --config docs/worknote/0.3.0/data-processing-routing/qualification/capacity/cap-7c-c5-policy.yml --output .dev/new-before-import --rows 100000 --shape collapse --import-artifact masks --processed-import --samples 3 --native-cost-cycle'
# Repeat each with the after JAR and a new output directory. --samples is the
# fixed native fork count. Commit the harness/policy and keep the same limits.
make native-cost-comparison COST_COMPARISON_ARGS='--before .dev/cap-bounded-before-docx/report.json --after .dev/cap-bounded-after-docx/report.json --output .dev/cap-bounded-docx-comparison.json'
make native-processing-profile JFR=.dev/cap-bounded-native-profile/0/diagnostic.jfr
```

Final source-quality evidence is recorded independently by `make verify`,
`make pmd-analysis`, the ownership-related `make pmd-watchlist`, `make docs`
and `make context` at the final report commit. Exact-HEAD verification freshness
must be checked there; passing these commands is not capacity acceptance.
