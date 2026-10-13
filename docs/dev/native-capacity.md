# Native SQLite capacity and verification boundaries

This guide records confirmed costs of the retained Native processing/storage
model and the limits of that evidence. It explains how to assess a workload
without confusing stage benchmarks, whole-service latency and JVM/cgroup memory.
It is not a portable throughput promise or a customer resource-acceptance policy.

## Measurement flow and boundaries

```text
owned physical input -> source processing -> canonical mutation/receipt
  -> mutable CSV -> immutable snapshot -> durable SMB publication
  -> independent readback and receipt recovery checks
```

Count input IOC occurrences **before** deduplication separately from final
artifact rows and physical SQLite rows. Report canonical completion, durable
remote completion and independent readback as distinct times. A local processed
import ending in export is not a measured SMB cycle. Stored revision timestamps
represent transaction effective time, not commit completion.

Primary timings exclude instrumentation when that series specifies it;
diagnostic JFR/agents, startup and post-service receipt/cleanup probes have
separate scopes. Calling-thread allocations, whole-JVM cumulative allocations,
sampled live heap, current RSS, lifetime VmHWM and shared cgroup charge are
not interchangeable. Shared charge includes collectors and file cache.
Cumulative allocations are churn, not simultaneously retained RAM.

The temporary implementation/experiment bundle and its measurement programs
were removed after selecting Native. The tables below preserve reviewed facts,
measurement identities and failed-screen dispositions. They are historical
observations of the named binaries, not fresh load evidence for every later
cleanup commit. Raw benchmark files are not versioned repository artifacts.
Supported operational smoke/load commands remain in [developer tools](../../tools/dev/README.md).

## Original incident and confirmed fixes

On 2026-10-04, installed release `860a3b2bdf3c-20261004T082925Z`
(JAR SHA-256 `8a1ddccb7ff669a56271719824e474ea1d44b30ab914cb027eb3ee28a1c12e43`)
processed `ioc-100000-seed-43.html`: 100,000 occurrences, 89,917 unique input
values and six types, 6,277,616 bytes, input SHA-256
`8e27297b0aed5bc6e4a9dad1e1c81e55caa09aeb8696328fdbaa42d6e4a2ec9b`.
Ingest ran from 08:59:56.153432238Z to 09:36:52.064Z; physical aggregate CSV
and `_SUCCESS` were verified. This approximately 36-minute-56-second incident
was not a clean empty-database benchmark or an SMB-upload-to-completion timer.
It cannot be divided by later million-input results to claim a speedup.

The old matcher combined per-row calls with an alias-wide query plan. An
exact-driver mechanism probe with 100,000 unrelated aliases measured 753.919 ms
per 32 requests for that query, versus 4.378 ms for a request-first join and
0.855 ms for complete-key direct lookup. This is SQL mechanism evidence, not
whole-service throughput. Native now uses indexed exact material checks and
transaction-owned matching/mutation statement sessions.

Other retained changes are bounded source streaming, disk reduction, bounded
semantic reuse, prebound mapping/conditions, native sequential Camel dispatch,
ordered receipt-position streaming and fewer row-decoder temporaries. None
removes the requirement to process relevant occurrences or atomically commit
canonical/provenance/receipt facts. [Processing](processing.md) and
[storage](storage.md) describe the current algorithms.

## Historical processing comparisons

The URL -> host/IP series used production Spring binding/preflight, real
extractor/classifier/Camel/mapping, URL ports/paths/query/fragment, repeated URLs,
different sources and 20 final domain/IP keys. Before production was `08d30621`;
after was `c279f885`, with identical probe/resources overlay. Sixty primary JVM
forks and four separate diagnostics passed independent fields/keys/winner,
receipt and provenance checks. Each table cell is a five-fork median.

| Profile | Before/after time ms | Before/after caller allocation MB | Before/after sampled heap MiB | Before/after current RSS MiB |
|---|---:|---:|---:|---:|
| First document, 8k occurrences | 1,724.1 / 1,565.8 | 476.7 / 356.3 | 116.2 / 113.1 | 371.4 / 380.8 |
| First import, 2k rows | 1,459.4 / 1,218.0 | 119.4 / 94.4 | 111.6 / 110.5 | 372.9 / 373.1 |
| Warm document, 8k occurrences | 795.3 / 695.1 | 433.7 / 314.2 | 130.9 / 120.2 | 404.6 / 382.3 |
| Warm import, 2k rows | 750.4 / 679.9 | 104.6 / 80.4 | 114.8 / 112.6 | 394.9 / 379.2 |
| Large warm document, 100k occurrences | 6,359.4 / 5,732.6 | 5,147.8 / 3,668.4 | 499.7 / 266.6 | 772.0 / 573.8 |
| Large warm import, 10k rows | 1,904.9 / 1,672.8 | 480.5 / 361.4 | 114.5 / 114.3 | 395.2 / 395.7 |

Allocation MB is decimal and cumulative. These revision-block measurements are
not alternating crossover pairs, include synchronous processing after Spring
startup, and do not include full transport/service cadence. There is no ratio
to an uncleaned path: it has different output semantics. Resource acceptance
remains separate ([OUT-4](../KNOWN-ISSUES.md)).

The native sequential Camel prototype used three alternating local service pairs
under the same 512-MiB heap and 768-MiB/1-GiB cgroup limits. Reference/prototype
JAR hashes were `0314687cfa6f6278af7696ec3c08c086005ac4754fba72e59c9567dea899383c`
and `e2242a33651d0d96cc4e996439364b4001a96f04fb80729cf712038ab3b505c6`.
Median service window was 62.764/60.043 s; CPU 54.460/52.200 s; sampled RSS
388,268,032/382,726,144 B; sampled heap 89,723,187/88,567,193 B. The final
packaged implementation separately measured 62.005 s and retained its failed
legacy time/writer-hold screens. The prototype ratio is not relabeled as final
implementation evidence.

## Bounded Native decoder comparison

One retained production change (`36672551`) reduced transient row decoding.
Before JAR at `22467cdd`: SHA-256
`1cfe167d9ba00f77c668361f9defce803c281ee2ad6319f3263e5ca1b4cec393`.
After JAR at `d13c3a7b`: SHA-256
`e9e49a2b1cd93e2243354021187e254945da85ef509a11591467ca84ccaa5b47`.
Only `DocumentRowCodec` production bytecode differed. Three forks per workload
used two CPU equivalents, 512-MiB heap, 768-MiB/1-GiB shared cgroup, zero swap,
and unchanged cache/row/disk limits. Independent full canonical/receipt/CSV/slot
checks agreed; variable UTC/occurrence IDs were excluded from cross-fork hashes.

| Metric, median | Before | After |
|---|---:|---:|
| 100k unique DOCX, local seconds | 70.988 | 141.006 |
| DOCX whole-JVM allocated bytes | 15,953,998,640 | 12,418,735,800 |
| DOCX sampled heap / RSS MiB | 92.60 / 380.70 | 91.58 / 378.62 |
| DOCX workspace / canonical WAL peak MiB | 144.18 / 190.53 | 144.31 / 190.54 |
| 100k processed import -> 20 hosts, local seconds | 18.280 | 32.425 |
| Import whole-JVM allocated bytes | 3,355,615,472 | 3,331,114,976 |
| Import RSS / workspace peak MiB | 345.08 / 133.97 | 344.58 / 154.77 |

The later untouched old-binary control took **154.704 s**, with allocations
within 0.22% of its earlier median; even unchanged read/refang/extract stages
slowed. The timing change was not isolated to the decoder. Retain the **22.16%
allocation reduction**, without claiming a stable end-to-end speedup. Separate
JFR field-decoding sampled weight fell from 5,145,007,024 to 1,127,139,752 B;
post-GC live heap stayed 42.37/42.22 MiB. All seven DOCX primary forks retained
FAIL against the old 45-s local/5-s hold/10-s wait screens. Import individual
screens passed, but the after median exceeded the historical 30-s series target.
These failed screens were not turned into acceptance.

## Complete-service Native observations

The later reference envelope used a shared four-CPU application/collector slice,
5-GiB high and 6-GiB hard memory limit, no swap, JVM Xms256m/Xmx2g, balanced
SQLite, and an explicitly larger 8-GiB workspace/32-GiB aggregate private-disk
allowance. WSL had roughly 16 GiB assigned. SMB was an actual server but loopback,
not a provisioned 1-Gbit LAN/reference NVMe machine. These are one-fork results;
resource profiles and corpus/cadence differ from the bounded table above.

| Input and completion scope | Seconds | Input/s | Whole-JVM allocation GiB | Bulk heap / RSS MiB |
|---|---:|---:|---:|---:|
| 100k distinct IOC, one DOCX, durable SMB completion | 65.56 | 1,525 | 11.72 | 243.92 / 691.64 |
| 1m distinct IOC, one DOCX, durable SMB completion | 705.29 | 1,418 | 116.87 | 207.96 / 868.16 |
| 1m IOC, 100 DOCX, workers/window 2/4, durable SMB completion | 640.05 | 1,562 | 385.31 | 266.07 / 820.97 |
| 1m IOC, 100 DOCX, corrected terminal CAS, workers/window 1/1, durable SMB completion | 909.48 | 1,100 | 389.93 | 297.97 / 774.31 |
| 100k detailed URL import rows -> 20 hosts, local export | 14.76 | 6,775 | 3.21 | 178.69 / 586.21 |
| 1m detailed URL import rows -> 20 hosts, local export | 117.80 | 8,489 | 31.68 | 193.43 / 630.53 |

The strict 1/1 batch originally failed terminalization CAS (ING-17). Its corrected
binary SHA-256 is `85f1cbd135eb2700c842b6513710b02c5469dd48be0d29713801651c04f241e3`;
only `DocumentAdmissionService` production code changed from its frozen baseline.
The successful rerun does not erase that failure or establish a causal 2/4-to-1/1
timing ratio. It checked 100 bulk completions, 102 receipts including warmups,
2,167,020 artifact rows, keys/provenance, CSVs, sparse slots, hashes and three
new successful SMB slices. There were no terminal CAS conflicts or cleanup
errors. Its complete receipt replay took 10.58 s; maximum promotion ownership
was 1.873 s, overall writer ownership 5.294 s in slots, CONTROL wait 4.899 s.
After 96 extra maintenance calls (0.117 s), dataframe size was 3,719.99 MiB and
named WAL 64 MiB. CPU was 866.26 CPU-s, GC 3.556 s, shared peak 5,120.20 MiB;
there were no max/OOM events or swap. Near-empty cleanup is not a sustainable
reclamation-rate proof.

The giant DOCX retained the opposite tradeoff: 169.205-s writer hold and
168.469-s control wait, despite its 11-minute-45-second completion. Receipt
replay read 2,166,987 rows in 7.98 s; after cleanup DB/WAL were
3,754/64 MiB. Splitting input changes lock occupancy, per-document work and
publication cadence; it cannot be presented as an isolated backend speedup.

The rejected versioned candidate completed its giant service interval in
1,934.65 s, then failed a 900-s full-receipt diagnostic; additional cleanup took
740.85 s and DB size was 8,024.54 MiB. Its shorter writer holds did not compensate
for copying/versioning/read/cleanup cost. This explains the Native choice; it is
not a supported second backend or a reason to adopt candidate UTC semantics.

## Current checks and remaining limits

`NativeDeliveryLifecycleIT` exercises production YAML/preflight, Tika/RE2/J,
classification, Camel, mapping, Native DBs, receipts, CSV/export and real ledgers;
only UTC and the SMB wire boundary are substituted. `RouterSelectedImportDeliveryIT`
connects processed-route configuration and physical CSV to COALESCE/canonical
receipt and receipt-only recovery. JDBC/TCK suites cover transactional rollback,
expiry equality, ID non-reuse, source/registration authority, field positions,
retention snapshots and connection ownership. Gates test correctness, not load SLO.

A normally packaged live Native smoke on 2026-10-13 separately passed an actual
SMB cycle (1,000 IOC in ten DOCX plus fixed additional/recovery inputs), with
14.91 s to durable completion and 18.02 s including independent readback.
Its exact identity and checks are in [ingestion](ingestion.md#verification-boundaries).
That smoke predates this source cleanup; offline checks do not create new live
transport evidence.

Known costs remain one SQLite writer, nonpreemptive large atomic promotions,
full projection/export work, per-occurrence mapping, allocations and file-cache
pressure, reader-pinned WAL, and workload-dependent retention. A 30-day policy
requires storage sizing against arrival rate, not one batch alone. The old
512-MiB heap/1-GiB cgroup/2-GiB workspace defaults are not qualified for every
million-input shape. [Bulk sizing](../guides/bulk-resource-sizing.md) describes
operator settings; changing them does not relax durability or safety checks.

Update this guide only with a named binary/configuration/corpus, precise timing
and resource scope, independent output/recovery checks and preserved failure
dispositions. Define actual production arrival rate, populated-base size and
control-delay requirements before claiming sustainable capacity or a p95/p99 SLO.

Related: [storage](storage.md), [processing](processing.md),
[ingestion](ingestion.md), [testing policy](../TESTING.md),
[known issues](../KNOWN-ISSUES.md), [ADR 0042](../ADR/0042-native-sqlite-production-storage.md).
