# Bounded typed chunk journal experiment

## Scope and hypothesis

This is the single next experiment after the accepted projection ACK mechanism.
It changes transfer evidence only: a complete typed chunk has one journal pointer
containing its actual effect count and checksum, rather than one pointer per
effect. Typed business rows, complete receipts, field positions and provenance
remain unchanged. `publication_chunk` still records the acknowledged ordinal
and checksum; `publication_fact` contains one `typed-chunk` pointer per chunk.
This is journal consolidation, not removal of business evidence.

The hypothesis is lower allocation, writer occupancy, WAL and cleanup debt in
receipt-heavy document and high-collapse processed-import workloads. It does
not promise that native preparation, typed version reads or receipts become
cheaper. One experiment does not establish a production resource envelope.

## Protocol and boundaries

- The existing publication protocol owns admission, manifest completeness,
  ordering, exact replay, dependency epochs, fencing, safe publication UTC,
  lifecycle renewal, atomic visibility and receipt recovery.
- The manifest still counts actual physical effects. The compact pointer carries
  their count plus a checksum of ordered typed values, SQL kinds, NULL, column
  names, family/key address, tombstone, publication-time flags and attempt stamp.
- Typed inserts and pointer/ordinal acknowledgement share one transaction.
  Failed typed insertion rolls back both. Replay compares the complete checksum
  before any typed insertion, preventing duplicate physical versions.
- A chunk that creates any anchored artifact marks the unit as needing validity;
  literal deadlines remain in typed rows, and the existing description pass
  retains the inherited minimum deadline. Publication expiry semantics are unchanged.
- `publication_journal_format` persists the choice. Compact configuration pins
  contain `chunk-journal-v1`; the former binary rejects these different pins.
  Existing candidate databases without the discriminator retain row-pointer mode.
  No in-place conversion of populated staged data is implemented or exercised.
- The production composition remains native. Compact selection is available only
  to the test launcher; ACK is direct in both compared variants. Receipt layout,
  dependency granularity and dispatcher policy are outside this experiment.

Transfer tests cover missing/extra/out-of-order chunks, changed replay content,
obsolete fences/stamps, SQL rollback, hidden visibility, mode/pin checks,
conflicting direct ACK, forced child-process loss and receipt-only recovery.
Checksums are retry-integrity evidence, not a promise to detect arbitrary external
SQL corruption of committed business data.

## Reproducible qualification

Use `make typed-journal-comparison JOURNAL_COMPARISON_ARGS='--baseline-jar
<frozen-ACK-jar> --baseline-helpers <frozen-original-helper-directory> --jar
<candidate-jar> --config <same-policy> --output .dev/<new-directory> --rows 10000
--samples 3'` after compiling the current helper sources.

The collector compares two real daemon profiles, with 100 initial seed rows:
10,000 unique DOCX occurrences and 10,000 processed CSV rows collapsing through
the host branch. Pair order alternates. Both use the same direct ACK port,
concurrent control/maintenance cadence, policies, workspace limits and effective
cgroup. No artificial 200-ACK warmup prefix precedes the bulk window.

The original JAR and original composition helpers are frozen before rebuilding;
metric endpoints and the admission agent are common to both. The candidate also
checks the attempt stamp explicitly in the shared protocol state query. That
indexed validation is a documented small code difference from the prior binary.

Independent output fields/keys/provenance, public slots and receipts are checked.
Additional deterministic fingerprints compare final canonical IDs/lifecycles,
aliases, field positions and receipt payload/order, excluding UTC and private
occurrence IDs. Failed windows remain failures and are never automatically retried.

Retain whole-window time/throughput, process allocations, sampled heap/RSS/cgroup,
writer wait/hold, named DB/WAL/private peaks, publication phase counts and physical
typed/journal census. Normal cleanup remains enabled; retained pointer counts are
not lifetime insert counts. After publication, 100 read-only real-port metadata
samples and exactly 96 extra rotating cleanup pages record read tails and remaining
debt outside the bulk window. These do not qualify broad production read p99 or
sustainable retention. Aggregate phase counts do not isolate phase CPU time.

## Results

Twelve forks and six alternating pairs completed with all semantic comparisons
passing. All temporary service state and frozen runtime directories were removed.
The `qualification/capacity/typed-journal/comparison.json`,
`qualification/capacity/typed-journal/summary.json` and
[raw evidence directory](qualification/capacity/typed-journal/README.md) retain
measurement identities and both favorable and unfavorable observations.

Baseline JAR SHA-256: `d8b361cf0e2524eb28d4c9d4c154dadc4b87ee08c450ed89821bebc04286ccbd`.
Candidate JAR SHA-256: `fbf249ad9e0a14f149345b3cfc8eb91bf5f333c650b2698ef41ad28b12552de3`.
Both are explicitly identified dirty-worktree builds at parent HEAD `46cb4ab3`.
The executable production sources did not change after measurement; two further
byte-budget regression cases were added before the final build-quality gate.
Do not substitute a subsequently repackaged JAR hash for these measurement IDs.

The effective envelope was two CPUs (`cpu.max=200000 100000`), 1 GiB cgroup
maximum, 768 MiB cgroup high and JVM `-Xms128m -Xmx512m`. UTC preflight observed
899 samples over 90 seconds without a detected backward step. Both variants
used the same policy SHA-256
`5114905f428ef4f22dcb9f732568373562738562a7775158053c154c2f71c5d9`.

### Whole-service document result

These are medians of three forks per variant. Heap/RSS/WAL/private peaks below
use only the bulk monotonic bounds; post-publication read/cleanup observations
are reported separately. Throughput is input occurrences per second.

| Metric | Row pointers | Chunk pointers | Change |
| --- | ---: | ---: | ---: |
| Local document-to-slices window | 24.554 s | 18.641 s | -24.1% |
| Input throughput | 407.3/s | 536.5/s | +31.7% |
| Process allocations in admission window | 3704.8 MiB | 3294.9 MiB | -11.1% |
| Total canonical writer hold | 9.229 s | 3.454 s | -62.6% |
| Promotion writer hold | 8.630 s | 3.088 s | -64.2% |
| Total writer wait | 1.617 s | 0.426 s | -73.6% |
| Maximum CONTROL writer wait | 27.38 ms | 12.42 ms | -54.7% |
| Writer transactions | 789 | 676 | -14.3% |
| Maximum individual writer hold | 66.12 ms | 36.12 ms | -45.4% |
| Sampled heap peak | 87.48 MiB | 87.19 MiB | approximately unchanged |
| Sampled process RSS peak | 378.71 MiB | 380.22 MiB | +0.4% |
| Named canonical WAL peak | 34.32 MiB | 22.19 MiB | -35.3% |
| Named private workspace peak | 56.88 MiB | 56.88 MiB | unchanged |
| Dataframe DB before extra cleanup | 172.57 MiB | 88.95 MiB | -48.5% |
| Committed effect count | 116,802 | 116,802 | unchanged |
| Retained physical typed versions | 136,803 | 116,802 | fewer obsolete attempt rows |
| Retained journal pointers | 136,803 | 490 | -99.64% |

Whole-window times were tightly grouped: row 24.552–24.644 s and compact
18.630–18.647 s. This supports the bounded document gain without claiming
performance at 100k/1M occurrences. Identical committed effect counts and
canonical/receipt fingerprints confirm that no business evidence was dropped.

There is an important attribution boundary. Both variants rebuild once under
the same concurrent control cadence, but they reach the conflict at different
points. Row mode already copied an additional 20,001 hash/alias/receipt/control
effects in the obsolete attempt; compact mode does not retain that extra copy.
The family census and 542 versus 462 bulk hidden-chunk events show the difference.
Consequently the observed time, allocation and DB gains include avoided obsolete
transfer work. They are valid whole-service results under this cadence, **not**
a controlled estimate of pure pointer consolidation with identical physical
retry work. No separate fixed-effect/no-control cost series was performed.

### High-collapse processed import result

| Metric | Row pointers | Chunk pointers | Change |
| --- | ---: | ---: | ---: |
| Local CSV-to-slices window | 7.635 s | 7.633 s | approximately unchanged |
| Input throughput | 1309.8/s | 1310.2/s | approximately unchanged |
| Process allocations | 417.51 MiB | 417.12 MiB | approximately unchanged |
| Total canonical writer hold | 36.33 ms | 65.73 ms | observed median increase |
| Promotion writer hold | 2.49 ms | 2.88 ms | +0.39 ms |
| Writer transactions | 54 | 54 | unchanged |
| Maximum individual writer hold | 2.88 ms | 30.77 ms | observed tail increase |
| Sampled heap peak | 83.60 MiB | 82.71 MiB | approximately unchanged |
| Sampled RSS peak | 371.02 MiB | 371.03 MiB | unchanged |
| Named canonical WAL peak | 4.02 MiB | 4.29 MiB | +6.8% |
| Named private workspace peak | 11.65 MiB | 11.80 MiB | +1.3% |
| Dataframe DB before extra cleanup | 3.42 MiB | 1.91 MiB | -44.2% |
| Retained journal pointers | 1,851 | 31 | -98.3% |

Do not hide the import writer observations. Total-hold ranges overlap:
33.48–68.28 ms for row mode and 37.87–67.11 ms for compact mode. The largest
spikes belong to **MAINTENANCE**, with 30–37 ms occurrences in both variants
(one row fork and two compact forks); the compact promotion maximum remains
1.92–1.98 ms. A three-fork median does not establish their cause or a general
writer regression. It also does not establish an import speed/allocation gain.
The import has only three hidden chunks in each variant, so bulk cost is mostly
outside the eliminated per-effect journal writes.

### Read and cleanup observations

Read scope is committed `masks` projection metadata through the real port.
The table contains median per-fork nearest-rank percentiles from 100 post-window
reads per fork. It does not represent all canonical query families.

| Profile | Row p95/p99 | Compact p95/p99 |
| --- | ---: | ---: |
| Document | 82.60 / 195.86 us | 114.60 / 179.51 us |
| Processed import | 83.73 / 158.39 us | 77.96 / 209.66 us |

Concurrent metadata read samples are also retained: document pooled p95/p99
0.314/0.410 ms (75 samples) versus 0.394/1.294 ms (62 samples); import
0.492/1.632 ms versus 0.310/0.343 ms (24 samples each). These mixed tails do not
prove a read improvement or satisfy a broad production read-latency requirement.
The indexed visibility query remains unchanged.

The same 96 extra cleanup/retention calls cost approximately 47 seconds in both
variants, outside the bulk time and allocation window. Two `$journal` visits
removed early unit dependency evidence; neither drained the bulk pointer prefix.
Remaining pointer counts were still 136,803 versus 490 for document and 1,851
versus 31 for import. Thus physical debt is smaller with compact evidence,
but cleanup throughput did not measurably improve in this bounded window.
The maintenance cursor visits one attempt per journal page; sparse/empty early
units and other family rotations consume budget. This is an observed remaining
limit, not authorization for another corrective cycle. No vacuum or sustainable
arrival-versus-cleanup workload was qualified.

### Correctness, quality and disposition

All twelve forks passed independent fields, complete keys, source/provenance,
mutable/immutable projection and slot oracles. Each pair also matched final
canonical IDs/lifecycles, aliases, receipt payload/order, import receipt counters,
rejection/warning ordinals and slot outcomes. The same selected host import ran
through real configuration, extractor/classifier/Camel and physical CSV.
Forced JVM-loss/reopen tests independently cover receipt-only recovery after
commit with the private stage removed; the load series does not inject crashes.

The mechanism is worth retaining as an isolated staged candidate: document
writer occupancy and storage shrink materially, while high-collapse import time,
allocations and sampled memory remain effectively unchanged. This is sufficient
mechanism qualification, not production activation or resource-budget acceptance.
Keep native as the production default. No automatic dispatcher, larger workload
extrapolation, append-only receipt conversion or further optimization cycle is
admitted by this result.

Final deterministic gates and analyzer review are recorded after the retained
measurement evidence is complete. SpotBugs finding `CAP-7C-C3-SB-010` changes
only method descriptor/hash/bytecode anchor due to the journal argument. Its SQL
still drops only quoted validated native-catalog identifiers, with adapter-owned
DDL. `TYPED-JOURNAL-SQL` records that same trust-boundary review; no exclusion or
broader suppression is introduced. The added IT expands the reviewed suite
universe to 222 fast / 82 integration / 299 deterministic classes (five external
shells remain skipped offline); coverage floors and missed-count ratchets stay unchanged.
