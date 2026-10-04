# O8 local processing optimization

Baseline: `29fa410e64850040406e6382806e39082e2dbde3`.
Scope: local computation and allocations after mass URL-to-host qualification.
Customer performance acceptance remains separate.

## Protocol fixed before the comparison series

Freeze compiled runtime files before changing production code. Keep separate
snapshots for baseline, lexical-key validation only, validation plus bounded LRU
reuse, and the final candidate with import attempt scopes. Snapshot manifests
identify source commit/diff, resources and every runtime file by SHA-256.
The initial one-pair snapshot runs are build/semantic smoke, not gain estimates.

Use five alternating reference/candidate forks for both document and import in
each profile: repeated domains (8k/20 document, 2k/20 import), unique domains
(8k/8k, 2k/2k), and host collapse (100k/25k URLs, 10k/2.5k URLs, 20 final hosts).
One disjoint warm-up precedes measured insertion in each JVM. Pin logging to
WARN, DEBUG/TRACE=false and heap to -Xms128m/-Xmx512m. No concurrent build or
other benchmark runs during primary measurement. Do not discard slow samples.

Compare complete selected-path signatures including outcome summaries (status,
diagnostic counts, severities and retained code counts), fields, CSV bytes, keys,
canonical IDs, provenance and import receipt/participants. Per-diagnostic messages
and contexts are not part of this benchmark oracle; regression tests cover error
and source-authority behavior separately.
Run separate agent/JFR forks with a common observer jar and a bounded retention
probe. Diagnostic timings do not establish performance gains. Preserve errors
and partial samples; a difference in signatures aborts qualification.

Admission criteria: no semantic regression; no increase in aggregate semantic
cache budgets; demonstrate reduced computation/allocation in repeated and
collapse workloads. Investigate unique-stream caller allocation regression over
10% or median elapsed regression over 15% before adoption. These are experiment
screens, not customer SLOs. Heap/RSS and per-pair spread remain reported even
when noisy. Isolated substep comparisons explain attribution separately.

## Changes under evaluation

- Validate canonical SHA-256 lexical form with an exact ASCII scan, avoiding
  per-key regex compilation while retaining rejection and null contracts.
- Partition the existing semantic budget between classification and host caches;
  bounded LRU replacement keeps recent originals and frequently used derived
  hosts available after saturation. Source/section/type/policy isolation stays.
- Give processed import an explicit preparation session per staging attempt,
  including authority checks and close-before-seal on all exit paths.
- Bind transform names/arguments once per mapper while preserving per-occurrence
  provider calls, gate order and reached-cell failures.
- Add an alternating frozen-runtime comparison facade; compare the full outcome summary
  rather than discarding it from selected-path equivalence.

## Architectural limits examined

Do not infer host derivation from arbitrary classification features: the current
feature extractor normalizes before parsing, whereas NetworkHostDeriver parses
the supplied value; MatchPolicy is pluggable. Sharing these results without a
new explicit domain contract can change outcomes. Parser fusion is not adopted
in this slice. Mapping every occurrence remains necessary for diagnostics and
whole-row selection. No early deduplication, row-result cache or Camel runtime
replacement is introduced. A separate transform-binding candidate moves specification parsing to mapper
construction without changing lazy gate/error behavior. Promotion requires at
least 5% median elapsed or 5% caller-allocation reduction in the collapse profile,
no semantic difference, and no >15% time regression in its other workload. Below
that screen retain the simpler existing mapper and preserve the rejected patch.
This screen is recorded before the five-pair candidate series completes.

## Results

The initial primary series was interrupted with 48 retained forks: repeat and
unique completed, collapse stopped before its fifth pair completed. No successful
report was published for that series. Keep `.dev/o8-primary/partial.json` and
`interruption.json`; the final candidate reruns the whole matrix rather than
selecting particular samples. The cause of the runner termination is not established.

Transform prebinding passed its isolated five-pair screen: document time ratio
0.9325 (all five pairs improve), allocation ratio 0.9980; import time ratio 1.0117,
allocation ratio 1.0006. It is retained for the complete final matrix. This is a
small CPU improvement without a meaningful allocation claim.

The final primary matrix completed all 60 forks with equal signatures in all
30 pairs. These compare the selected production path at the baseline with the
same selected path plus all four local optimizations. They do not compare the
compatible path with the router.

| Profile / workload | Median elapsed before → after | Caller allocations before → after | Time ratio | Allocation ratio |
| --- | --- | --- | --- | --- |

| repeat / document | 0.367 → 0.359 s | 244.1 → 217.4 MB | 0.9778 | 0.8908 |
| repeat / import | 0.597 → 0.552 s | 76.3 → 66.4 MB | 0.9238 | 0.8707 |
| unique / document | 2.175 → 2.135 s | 1775.8 → 1693.6 MB | 0.9820 | 0.9537 |
| unique / import | 0.963 → 0.962 s | 266.3 → 257.1 MB | 0.9996 | 0.9655 |
| collapse / document | 4.658 → 4.132 s | 3665.2 → 3065.7 MB | 0.8872 | 0.8364 |
| collapse / import | 1.551 → 1.509 s | 361.9 → 307.6 MB | 0.9726 | 0.8501 |

MB denotes decimal cumulative allocated bytes on the calling thread, not live
heap and not all JVM threads. The largest profile's document sampled peak heap
increased from 252.4 to 264.5 MB (+4.8%); RSS high-water median increased from
561,520 to 568,708 KiB (+1.3%). Import heap was essentially unchanged. The largest
RSS regression across the matrix was repeat import (+1.8%). Unique streams pass
the predeclared time/allocation screens. Small timing differences are local
observations from five pairs, not statistical significance or production SLOs.

## Attribution and reproducibility

The isolated key-validation comparison (five pairs, 100k-occurrence document)
shows elapsed ratio 0.9314 and caller-allocation ratio 0.8902. The exact lexical
scan removes repeated regex construction; the regression tests include wrong
length, uppercase/non-ASCII characters and trailing whitespace. This gain does
not require any semantic cache hit.

[Raw measurements and runtime manifests](qualification/optimization/o8-local-optimization.json)
retain every sample, paired ratio, range, environment, fixture/config fingerprints,
frozen runtime file hashes and the excluded interrupted series. Full fork logs,
SQLite workspaces and signatures remain under `.dev/o8-final-primary`; the
separate mapper experiment is `.dev/o8-mapper-isolated`.

Create each frozen snapshot with `make processing-route-comparison` on the
respective source revision and a fresh workspace (a one-pair selected-only
host-collapse run is sufficient to freeze the runtime). Baseline is the commit
above; candidate is this change. Then run:

```bash
make processing-optimization-comparison COMPARISON_ARGS='--reference .dev/o8-baseline-snapshot --candidate .dev/o8-mapper-snapshot --workspace .dev/o8-final-primary --pairs 5'
```

Use new empty workspace paths for repetitions. The driver verifies that runtime
files are unchanged at the end and compares identical effective config per pair.
Snapshot-building runs do not count as primary measurements. The snapshot named
`o8-final-snapshot` predates transform binding; `o8-mapper-snapshot` is the final
candidate. Substep order is lexical key check → LRU partitions → import session
→ transform binding; isolated medians from different series must not be added
or multiplied to predict the final result.

The other isolated five-pair series confirm:

| Incremental change | Workload | Time ratio | Caller allocation ratio |
| --- | --- | --- | --- |
| LRU partitions after key validation | collapse document | 0.9492 | 0.9380 |
| Attempt scope after LRU | collapse import | 0.9917 | 0.9016 |
| Transform binding after attempt scope | collapse document | 0.9325 | 0.9980 |
| Transform binding after attempt scope | collapse import | 1.0117 | 1.0006 |

There are 110 successful primary forks (60 final matrix, 30 isolated key/cache/
import-session, 20 isolated mapper), plus four separate instrumented/JFR forks.
The 48 interrupted forks and snapshot smoke runs are excluded from gain claims.

## Why computation decreased

A common observer jar instruments both baseline and candidate. Counters cover
only the measured invocation; startup and warm-up are excluded. The new import
preparation seam is detected explicitly so its timer does not double-count the
single-row forwarding entry point. Diagnostic timings are not primary evidence.

| Counter | Document before → after | Import before → after |
| --- | --- | --- |
| Classification computations | 149,902 → 50,040 | 20,000 → 5,020 |
| Derived classifications | 50,020 → 40 | 10,000 → 20 |
| Host derivations | 99,646 → 50,000 | 10,000 → 5,000 |
| Network parser calls | 249,548 → 100,040 | 40,000 → 20,020 |
| Mapped candidates | 350,000 → 350,000 | 10,000 → 10,000 |
| Mapped cells | 2,100,000 → 2,100,000 | 100,000 → 100,000 |

The document's 40 derived classifications reflect 20 hosts under two source
contexts. The import fixture produces 20 distinct host/source pairs. Source is
still part of the classification key. Adjacent repeated URLs now reuse results
after cache saturation; distinct URLs still require parsing. The surviving 350k
mapping candidates explain why the overall gain is much smaller than the fall
in classification computations. Import still pays staging/SQLite/canonical
coordination costs on every row; reduced preparation allocations alone do not
produce a comparable end-to-end speedup.

No remote cache is needed for these deterministic local computations. Adding
Redis would add serialization, network calls, failure handling and shared-cache
key/version ownership without removing required mapping or staging. A shared
cache would need a separate measured cross-invocation reuse case and explicit
policy/source isolation; it is outside this change.

## Retention and verification

The separate retention probe covers 8k duplicate, unique and oversized inputs,
then four concurrent independent sessions. It asserts each partition's entry
and charged-byte limits. Results: duplicate 40 entries / 348,420 charged bytes;
unique 158 / 1,042,444; oversized 0 / 0; concurrent 632 / 4,169,776 across four
sessions. No partition exceeds 128 entries or 512 KiB. These are conservative
accounting limits, not measured deep object sizes or service heap ceilings.

The frozen final runtime matches all 1,323 corresponding production class files
from the verified reactor. This check uses current modules, excluding stale
output directories of removed modules. Qualification ran on 2026-10-03–04
(Asia/Irkutsk). No production source changed after freezing the final runtime.

Validation completed:

- Focused core/processing tests and the real selected-import delivery IT.
- Full `make verify`: 287 deterministic offline suites; aggregate coverage
  91.00% lines / 82.72% branches; lifecycle and coverage integrity checks pass.
- SpotBugs raw report reviewed: 120 accepted / 0 visible. The changed mapper
  retains existing false positive SB04-043 on `header()` returning its immutable
  snapshot; no new suppression or baseline change.
- CPD: 24/24 groups; adopted PMD: 0 blocking, 24/24 advisory. No findings in
  changed members; the separate PMD ownership/size watchlist has 30 advisory
  findings and none in the changed classes.
- ShellCheck, packaging/tools contracts and all 15 comparison-tool tests pass.

The first full verify ran while this evidence document was being finalized,
so it does not establish an unchanged-worktree freshness record. Final unchanged-worktree validation uses `make docs`, `make verify` and
`make pmd-analysis` after all report edits; `make context` records the final
worktree fingerprint freshness. Logs and runtime workspaces
are local evidence, not provisioned SMB or crash-injected JVM evidence.

## Remaining limits

Customer resource acceptance (OUT-4) remains open. These measurements establish
local gains on controlled inputs, not throughput under multiple simultaneous
large imports/documents or a production memory limit. The document still maps
350k candidates and allocates about 3.07 GB cumulatively on the calling thread.
The next optimization should profile this retained per-occurrence mapping cost,
not enlarge a cache blindly. Sharing parse results requires an explicit domain
contract first; externalizing cache state is not justified by this experiment.
