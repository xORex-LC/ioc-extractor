# O7 final optimization qualification

Status: qualification executed, 2026-10-03; customer performance acceptance is
deferred and historical resource guards are not all satisfied. Production
candidate starts at `4dd238f4`.
O1–O5 are adopted; O6 remains rejected. This qualification introduces no runtime,
policy, dependency or schema change. Customer performance acceptance is separate.
The subsequent [mass URL-to-host/IP extension](o7-host-collapse-qualification.md)
qualifies detailed URL collapse with the host view in both selected revisions.
Its measurements are separate; none replace or pool the original matrix below.

## Protocol fixed before sampling

Use the existing production Spring comparison, real extractor/classifier/Camel,
physical documents/CSV and private SQLite/workspaces. Pin `DEBUG=false`,
`TRACE=false`, `LOGGING_LEVEL_ROOT=WARN`, JDK 21 and `-Xms128m/-Xmx512m`.
Five fresh JVM pairs alternate compatible/selected order for every primary
profile. Warmed profiles first insert a disjoint fixture in the same context;
measured inserts must not become canonical no-ops or receipt replay.

| Profile | Document rows / distinct | Import rows / distinct | Warmups | Shape |
|---|---:|---:|---:|---|
| first | 8,000 / 20 | 2,000 / 20 | 0 | domains, host cleanup |
| warm | 8,000 / 20 | 2,000 / 20 | 1 | domains, host cleanup |
| half-repeat | 8,000 / 4,000 | 2,000 / 1,000 | 1 | domains, host cleanup |
| unique | 8,000 / 8,000 | 2,000 / 2,000 | 1 | domains, host cleanup |
| small | 1,000 / 20 | 1,000 / 20 | 0 | domains, host cleanup |
| large | 100,000 / 20 | 100,000 / 20 | 0 | domains, host cleanup |
| mixed | 1,000 / 20 | 1,000 / 20 | 0 | original-view routing |
| long | 1,000 / 20 | 1,000 / 20 | 0 | original-view routing, 4 KiB URL paths |

Mixed documents contain domains, bare IPv4, URLs and MD5; mixed imports contain
masks-admissible domains/URLs. Original-view routing in mixed/long profiles makes
public fields comparable to the compatible configuration; those profiles do not
measure host cleanup and must not be pooled with the domains profiles.
Multi-input carriers, explicit NULL, source authority, filtered/blocked/recovered
outcomes and receipt-only recovery belong to the existing semantic test corpus,
not a fabricated timing ratio between inequivalent policies.

Repeat the first and warm profiles on original production revision `08d30621`
with the exact same comparison probe/script/resources. Identify the test/tools
harness overlay in the manifest; it must not modify production sources. Compare
full selected signatures across revisions and contemporary compatible ratios.
Previous O0–O6 measurements explain isolated effects, but different logging or
measurement modes cannot establish the final combined timing gain.

Use the existing neutral Router probe for 1/4/16/64 branches and 1/4 callers,
SUCCESS/FAILURE/RECOVERY; keep its results separate from IOC workload acceptance.
Re-run the session retention probe for duplicate/unique/long/4-caller profiles.
These are mechanism/capacity evidence, not full concurrent application throughput.
Run a separate instrumented/JFR duplicate-heavy profile; do not use its timing
as a primary result. Preserve all samples, failing reports and profile dispositions.

The unchanged historical guards (2x time, 3x caller allocation, 1.25x sampled
heap/VmHWM, 1 GiB VmHWM) detect regressions only. The original O0 acceptance
proposal is 1.50x first / 1.25x warmed time, 1.50x caller allocation, 1.15x
sampled heap/current RSS, 256 MiB heap / 512 MiB current RSS for the two base
profiles. The operator explicitly deferred budget agreement on 2026-10-03: O7 is
qualification only. Do not retroactively widen the proposal after sampling.
A missed or unagreed budget leaves performance acceptance open.

## Results and risk dispositions

The [reviewed measurement bundle](qualification/optimization/o7-final.json)
retains every primary sample, manifest, median/range/paired ratio, original
reference, supplementary series, diagnostic counters and JFR triage. Initial
primary profiles use clean `41a22a7c`; supplementary/diagnostic and final neutral
profiles use `8ee4e4d8`. Production sources are identical to `4dd238f4` across
those qualification commits. Original references run `08d30621` with the same
six comparison harness/resource files and an added missing Make target; the
overlay modifies test/tools sources only and is recorded by digest/dirty state.

Eight initial profiles, two original-reference profiles and two supplementary
profiles ran five alternating pairs for both document and import: 240 primary
JVM forks. Four additional agent/JFR forks are diagnostic evidence only.
All full projection/key/ID/provenance signatures match within matched-policy
pairs. Both original references match optimized per-path outcomes, signatures,
input/configuration/runtime/logging identities in every pair. Additional SQLite
checks extend the retained import reports with internal IDs and source
occurrence accounting, including supplementary/diagnostic forks; all match.
Those queries occur after execution and do not change measured workload cost.
The permanent comparison signature now includes that import accounting too.

### Combined selected-path effect

This uses fresh original references with identical harness/inputs/logging/JVM;
it does not pool earlier O0 logging or instrumented timing with primary forks.
Allocation is calling-thread bytes, expressed below in decimal MB.

| Selected workload | Original / optimized time | Original / optimized allocation | Time change | Allocation change |
|---|---:|---:|---:|---:|
| First document | 1,354.4 / 1,046.4 ms | 429.1 / 281.9 MB | -22.7% | -34.3% |
| Warm document | 553.8 / 381.5 ms | 387.7 / 244.1 MB | -31.1% | -37.0% |
| First import | 1,345.0 / 1,111.0 ms | 119.3 / 90.3 MB | -17.4% | -24.3% |
| Warm import | 683.1 / 571.6 ms | 104.4 / 76.1 MB | -16.3% | -27.1% |

These are local medians, not portable latency guarantees. Current RSS medians
are effectively unchanged: first document -0.1%, first import +3.0%, warm
document -4.7%, warm import -0.3%. Warm document sampled heap is 14.6% lower
than original; startup spread and GC/sampling prevent a general memory claim.
Earlier [O0–O2](o0-o2-implementation.md), [O3–O4](o3-o4-implementation.md) and
[O5–O6](o5-o6-implementation.md) preserve isolated effects and their limitations.

### Contemporary compatible reference

All ratios below use medians from the initial five pairs per profile.
Matched public/canonical results do not make diagnostics identical: compatible
emits duplicate-skip warnings; selected continues mapping every occurrence.
URL overlap DEBUG diagnostics are retained in both paths.

| Profile | Document time / allocation ratios | Import time / allocation ratios | Historical guard disposition |
|---|---:|---:|---|
| First | 1.498 / 1.987 | 1.388 / 1.206 | pass |
| Warm | 1.347 / 2.126 | 1.195 / 1.217 | pass |
| Half-repeat | 1.168 / 1.203 | 1.123 / 1.082 | document sampled heap fails |
| Unique | 1.118 / 1.123 | 1.088 / 1.056 | document sampled heap fails |
| Small | 1.375 / 1.505 | 1.317 / 1.192 | pass |
| Large | 1.980 / 4.043 | 1.095 / 1.211 | document allocation fails |
| Mixed | 1.409 / 1.391 | 1.256 / 1.125 | pass, original-view policy |
| Long | 1.097 / 1.513 | 1.101 / 1.031 | pass, original-view policy |

Half-repeat and unique sampled heap was noisy, so two additional complete
five-pair series were declared and collected without discarding initial failures.
Half-repeat supplementary guards pass; unique supplementary guards fail.
Pooled ten-pair document heap ratios are **1.254685** and **1.293667** respectively,
still above the unchanged 1.25 historical guard. Half-repeat is borderline:
paired heap ratios span 0.961–1.329 and median-ratio/ratio-of-medians differ.
Unique paired ratios span 1.122–1.426. This establishes a qualification limit,
not a proven memory leak or precise retained-size diagnosis.

Large document selected allocation is 2,982.5 MB versus 737.7 MB compatible,
with 3,544.8 versus 1,790.2 ms workload time. Input throughput is approximately
28.2k versus 55.9k occurrences/s. Sampled heap is 152.9 MiB despite the much
larger cumulative allocation: bounded retention does not remove transient churn.
Every large-import participant is still staged: 20 ACCEPTED representatives
and 99,980 COALESCED participants. The canonical receipt confirms 20 accepted
mutations and zero rejected rows, with complete terminal state/warning checks. All import profiles remain within historical guards.

The initial proposal is still missed on base document allocation and warmed
time. The operator explicitly chose qualification only; no customer acceptance
budget is agreed, replaced or widened here. Passing build gates, improving the
original selected path or passing a historical guard does not authorize a
performance-acceptance claim. OUT-4 records the remaining published disposition.

### Mechanism and capacity evidence

The separate 100k-document/10k-import diagnostic forks confirm:

| Selected document observation | Count |
|---|---:|
| Actual classifications / host computations | 20 / 20 |
| Derived classification requests / actual derived computations | 100,000 / 0 |
| Mapped candidates / mapped cells | 300,000 / 1,700,000 |
| Template sends / recipient calls | 200,000 / 400,000 |
| Prepared rows / reserved mask IDs | 60 / 20 |
| Maximum winners per artifact | 20 |

Compatible prepares 100,040 candidates and 500,240 cells on the same document,
including every aggregate occurrence. Import retains row-local scope: 10,000
host computations and 10,000 actual classifications for 10,000 selected rows;
delivery-wide reuse is deferred. Counters show that reuse/retention improved
without bypassing input validation or durable import participants.

JFR selected document has 601 allocation and 90 execution samples with a visible
measured-call stack; import has 294 and four. Startup/warm-up stacks are excluded
using the probe's measured-call source lines; truncated stacks are excluded too.
Allocation weights are estimates; neither percentages nor exact process totals
are inferred. Exchanges/header maps and canonical-key construction occur in
allocation samples. A concrete follow-up is `CanonicalKeyMaterial`'s
`keyHash.matches("[0-9a-f]{64}")`: each construction recompiles a regex. Preserve
its lexical contract when assessing a future optimization. No extra production
optimization or framework replacement is introduced in O7.

The clean [1k](qualification/optimization/o7-router-1000.csv) and
[100k](qualification/optimization/o7-router-100000.csv) neutral matrices each cover
24 profiles: 1/4/16/64 branches, 1/4 callers and success/failure/recovery.
All expected candidates/blocked/recovered totals and positive allocation metrics
were checked. Metadata is retained for
[1k](qualification/optimization/o7-router-1000.metadata) and
[100k](qualification/optimization/o7-router-100000.metadata).
The previous neutral series had `dirty=true` from an analysis-generated Python
bytecode cache only; its complete raw files remain in `.dev/o7-neutral-dirty`.
Both whole matrices were repeated from a clean identity, without selecting
particular timing samples. These are mechanism diagnostics, not IOC SLOs.

[Session retention calibration](qualification/optimization/o7-session-budget.txt)
again shows 40 entries / 348,420 charged bytes for repeated keys, 120 entries /
1,045,460 bytes for unique keys, zero admission for 40k-character oversized
inputs, and 480 entries / 4,181,840 bytes across four independent sessions.
Bounds remain 256 joint entries, 1 MiB charged retention and 64 KiB per entry.
Oversize/full cache computes normally. Charged sizes and post-GC calibration
heap are not full-process heap limits; calibration uses a fixture classifier.

### Semantic coverage and remaining boundaries

The existing deterministic corpus covers source/section/type/policy isolation,
changed-source duplicates, losing-candidate failures, final-key ordering and
whole-row metadata, legacy deduplication disabled, ID/provenance accounting,
compound carriers/NULL/source authority, fragment rejection, COALESCED warnings,
FIRST blocked/EXCLUSIVE ambiguity/ALL preselection, typed recovery and branch
message isolation, stopped routes, bounded shutdown and exceptional MDC cleanup.
The full gate includes real Spring/YAML selected import, physical CSV, actual
extractor/classifier/Camel, private SQLite stores, canonical COALESCE/receipt and
receipt-only finalization after removing the stage. Full signatures complement
this corpus; a benchmark alone does not establish those negative/recovery cases.

O1 invariant binding, O2 linear replies/ownership, O3 winner retention, O4 bounded
semantic sessions and O5 metadata/secondary allocations remain adopted. O6's
one-entry prototype stays rejected, with no toggle or second runtime. Import
staging/commit/receipt/lifecycle ownership and semantic fingerprints are unchanged;
optimization rollback follows the existing drain/pinned-contract procedure,
never a DB restore or transparent disabling of the selected policy.

Remaining limits are explicit:

- Three document profiles exceed historical resource guards. No early dedup,
  suppressed diagnostics, increased cache budget or relaxed gate hides them.
- Base host-cleanup timing uses already-bare domains. Mixed/long timing retains
  original views for equivalent outputs; it does not qualify URL/IP detail-heavy
  cleanup performance. The subsequent extension qualifies a specific detailed
  URL-collapse workload with multiple sources; choose the customer's representative
  workload/concurrency/resource budget before acceptance.
- Compatible import disables contract selection but retains the configured plan
  catalog, which starts idle Camel too. Its startup/RSS comparison is incremental
  path cost, not a no-Camel/Camel deployment comparison.
- Concurrent Router and session calibration do not establish concurrent full
  application throughput or memory SLOs. No new parallel dispatch is enabled.
- Main-thread allocations omit other threads/native allocation; 10 ms heap/RSS
  sampling can miss shorter peaks. Five/ten forks do not establish p95.
- Physical inputs are deterministic HTML/CSV, not arbitrary Word/parser-bomb
  data or provisioned SMB. External transport qualification is separate.

## Reproduction and verification

For each row of the fixed matrix above:

```bash
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make processing-route-comparison \
  COMPARISON_ARGS='--pairs 5 --workspace .dev/new-o7-first'
# Add the row's --warmups, --document-rows/unique, --import-rows/unique and --shape.
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make processing-route-comparison \
  COMPARISON_ARGS='--diagnostics --pairs 1 --warmups 1 --document-rows 100000 --import-rows 10000 --workspace .dev/new-o7-diagnostic'
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make router-qualification SIZE=1000
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make router-qualification SIZE=100000
```

Original production measurement requires a detached `08d30621` worktree and the
six harness/resource files from `41a22a7c` listed in the JSON manifest. Copy only
those files and append the same Python comparison Make target (absent there).
Use the identical first/warm flags, new workspace and pinned environment. The
original harness overlay is intentionally dirty and must remain test/tools-only.
The entry-count calibration command is retained in [O4 evidence](o3-o4-implementation.md).
JFR extraction uses `jfr print --json --stack-depth 128 --events
jdk.ObjectAllocationSample,jdk.ExecutionSample`; measured document/import call
lines are 86/87 in the retained `ProcessingRouteComparison` probe.

Raw logs/JFR/DBs remain under the JSON's ignored workspace paths. The failed mixed
measurement at `f8c6bb0f` expected only duplicate diagnostics and stopped before
a complete pair. Its failure manifest and log are retained; the fixed harness
counts supported URL overlap DEBUG diagnostics and has a regression. An initial
original-reference attempt also stopped because `08d30621` lacked the Make
facade target; its log is retained before adding that test/tools-only overlay.
Fork timeouts retain partial logs and propagate failure, never publish a success.

Quality commands and reviewed analyzer disposition are recorded below after the
final report change. No module/dependency, test inventory, analyzer acceptance,
coverage baseline/floor, schema or operator configuration is changed.

### Build-quality checkpoint

`make verify` passed on the O7 worktree; the final committed HEAD is checked
again after this evidence commit. The existing real-Spring selected-import and
customer document suites execute in the full gate, alongside the preserved
negative/recovery corpus. `make pmd-analysis`, `make pmd-watchlist`, `make
lint-shell` and `make docs` passed too. Analyzer reports were inspected:

- Test/report integrity: fast 220, integration 72, external 5,
  deterministic-offline 287. Provisioned external shells remain skipped offline.
- Coverage: 24,359/26,777 lines (90.97%), 8,506/10,291 branches (82.65%);
  all 21 aggregate and 20 local report floors/missed-count ratchets pass.
- Raw SpotBugs: 120 accepted, zero visible; CPD: 24/24 duplication groups;
  adopted PMD: zero blocking, 24/24 advisory; watchlist: 30 findings.
  Counts are unchanged, with no findings intersecting the changed Java test
  members. No production Java or analyzer universe changed in O7.
- Packaging/tools contracts pass, including nine Python comparison contracts;
  documentation link check reports zero errors.

Logs are `.dev/o7-verify.log`, `.dev/o7-pmd.log`, `.dev/o7-watchlist.log`,
`.dev/o7-lint-shell.log` and `.dev/o7-docs.log`. Final exact-HEAD verification
uses `.dev/o7-final-verify.log` / `.dev/o7-final-pmd.log` and `make context`.
These deterministic offline checks do not convert resource-guard failures into
performance acceptance, and they do not claim provisioned external evidence.
