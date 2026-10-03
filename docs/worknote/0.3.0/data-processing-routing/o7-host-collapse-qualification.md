# O7 mass URL-to-host/IP qualification

Status: qualification executed, 2026-10-03; protocol committed before primary
sampling at `c279f885`. This extends O7 qualification only; customer resource acceptance remains
deferred. No production processing policy or implementation is changed.

## Workload and independent semantic oracle

Use the production Spring comparison probe, real YAML binding/preflight, selected
preparer, extractor/classifier/Camel, physical HTML/CSV and isolated SQLite stores.
All URLs carry a scheme, port, unique path, query and fragment. Half of the final
addresses are domain hosts and half IPv4. HTTP and HTTPS variants share final
hosts. Consecutive repeated URLs and different URLs of each host are retained.

The document repeats the same URL set under `БИБ-0001` and `БИБ-0002`. All network
artifact branches consume `host`; masks explicitly admits bare IPv4 so both input
families have a primary output. Masks/IP/blacklist KEEP_FIRST must retain the first
source; aggregate LAST_NONEMPTY must retain the second source and complete row.
Independent Python assertions check final carrier fields, configured match codes,
SHA-256 keys over canonical JSON and the selected source's provenance. Each winner
contributes one canonical occurrence; this is not the number of extracted URLs.

Processed import uses the same host view and masks admission. Each host has a
stable `Feed Alpha` or `Feed Beta` label so the configured fill-missing COALESCE
policy remains compatible. Every CSV row is staged; representatives/COALESCED,
receipt counts, warnings, terminal state, IDs and canonical provenance are checked.
Different labels on the same host are exercised by the document, not artificially
merged across incompatible import source cells.

Each document URL creates one expected DEBUG overlap diagnostic. Total severity,
retained count/code and the full outcome summary are preserved. No warning is
discarded or early deduplication added. Warm-up final hosts/IPs are disjoint.

## Measurement protocol

| Profile | Document rows / distinct URLs / final hosts | Import rows / distinct URLs / final hosts | Warmups |
|---|---:|---:|---:|
| First | 8,000 / 2,000 / 20 | 2,000 / 500 / 20 | 0 |
| Warm | 8,000 / 2,000 / 20 | 2,000 / 500 / 20 | 1 |
| Large warm | 100,000 / 25,000 / 20 | 10,000 / 2,500 / 20 | 1 |

Collect five fresh JVM samples per workload/revision/profile. Compare original
production `08d30621` and the retained O1–O5 implementation with byte-identical
test/tools harness, input/configuration digests, dependency versions, logging and
JVM flags. The baseline overlay changes test/tools files only. Select the host
route in both revisions: compatible processing without cleanup produces different
fields and is not a valid comparator. Selected-only reports leave compatible
ratios and historical envelope status null, rather than fabricating acceptance.

Primary forks pin `DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN` and
`-Xms128m -Xmx512m`; startup is separate and no concurrent build/benchmark runs
during sampling. Time, throughput, caller allocation, GC, sampled heap/current RSS
and historical VmHWM are retained. Cross-revision comparisons require full equal
signatures and outcomes in every sample. Collect separate agent/JFR samples on
the large warmed profile in both revisions; their timing is diagnostic only.

The selected-only driver runs one revision at a time. Record the actual series
order; independent samples are not an alternating before/after crossover and no
portable latency/whole-process allocation claim follows. Preserve all failures
and raw logs. Resource-budget compliance is evaluated separately.

## Reproduction

```bash
DEBUG=false TRACE=false LOGGING_LEVEL_ROOT=WARN make processing-route-comparison \
  COMPARISON_ARGS='--shape host-collapse --selected-only --pairs 5 --document-rows 8000 --document-unique 2000 --import-rows 2000 --import-unique 500 --workspace .dev/new-cleanup-first'
# Warm: add --warmups 1 and use a new workspace.
# Large: --warmups 1 --document-rows 100000 --document-unique 25000
#        --import-rows 10000 --import-unique 2500
# Diagnostic: add --diagnostics --pairs 1 to the large profile.
```

Use the same driver/probe/agent/resources overlay in an isolated `08d30621`
worktree. All generated logs/JFR/DBs stay under `.dev`; only reviewed reports
are retained in the execution bundle. Original O7 measurements remain unchanged.

## Executed evidence and results

The [reviewed bundle](qualification/optimization/o7-host-collapse.json) retains
all 60 primary fresh-JVM samples and four separate agent/JFR samples, manifests,
series order, raw time/throughput/allocation/heap/current RSS/VmHWM/startup/GC
metrics, computation counters and independent validation source. It includes
final fields, canonical keys/IDs, projection digests and provenance for each
profile. Thirty primary cross-revision comparisons and two diagnostic comparisons
confirm equal inputs/configuration/outcomes/signatures. Warm-up bytes also match.
All four JFR recordings are readable; recordings/raw logs/SQLite stay ignored.

Original production is `08d30621`; optimized production is the retained O1–O5
implementation at `c279f885`. The original overlay uses identical probe, agent,
driver and resources, plus the missing Make target. Its recorded dirty state is
intentional: only test/tools files change. The first command in the fresh baseline
worktree failed shell redirection because its `.dev` directory did not exist;
no build or measured fork ran. Creating the directory resolved this setup error.
All complete measured series succeeded; no performance sample was discarded.

### Primary cost: before / after

Allocation below is cumulative calling-thread decimal MB, not retained heap.
Heap/current RSS are sampled MiB. Each cell uses five independent JVM medians.

| Profile / workload | Time ms | Throughput observations/s | Allocation MB | Heap MiB | Current RSS MiB |
|---|---:|---:|---:|---:|---:|
| First document | 1,724.1 / 1,565.8 | 4,640 / 5,109 | 476.7 / 356.3 | 116.2 / 113.1 | 371.4 / 380.8 |
| First import | 1,459.4 / 1,218.0 | 1,370 / 1,642 | 119.4 / 94.4 | 111.6 / 110.5 | 372.9 / 373.1 |
| Warm document | 795.3 / 695.1 | 10,059 / 11,508 | 433.7 / 314.2 | 130.9 / 120.2 | 404.6 / 382.3 |
| Warm import | 750.4 / 679.9 | 2,665 / 2,942 | 104.6 / 80.4 | 114.8 / 112.6 | 394.9 / 379.2 |
| Large warm document | 6,359.4 / 5,732.6 | 15,725 / 17,444 | 5,147.8 / 3,668.4 | 499.7 / 266.6 | 772.0 / 573.8 |
| Large warm import | 1,904.9 / 1,672.8 | 5,250 / 5,978 | 480.5 / 361.4 | 114.5 / 114.3 | 395.2 / 395.7 |

Document time medians improve 9.2–12.6%, caller allocation 25.2–28.7%.
Import time improves 9.4–16.5%, allocation 21.0–24.8%. Large document sampled
heap improves 46.7% and RSS 25.7%; other profiles do not show a uniform memory
gain. The baseline large warmed document approaches the configured 512 MiB heap.
The optimized large workload still allocates 3.67 GB and reaches sampled
266.6 MiB heap / 573.8 MiB current RSS. These are local cost results, not a passed
or failed customer resource budget. Revision blocks and host/GC variation limit
causal timing claims; min/max and raw samples remain available in the bundle.

### Semantic outcomes

Each document produces exactly 20 masks, 10 IP rows, 20 blacklist rows and 20
aggregate rows, plus a disjoint set after warm-up. Masks/IP retain `БИБ-0001`;
aggregate retains `БИБ-0002`, proving last-nonempty replacement of the whole
candidate. Blacklist host/IP carriers and aggregate host/IP carriers contain no
scheme, port, path, query or fragment. Independent expected-key hashes prove
that distinct URLs share the same final identity. Prepared winners reserve 30
mask/IP IDs in the measured diagnostic document. Each canonical winner has one
provenance occurrence under its winning source; losing observations still map.

Document diagnostics are exactly one DEBUG extraction-overlap per URL occurrence:
8,000 in the base profiles and 100,000 in the large profile. The existing 10,000
diagnostic retention cap preserves 10,000 overlap records and the suppression
marker, with 90,000 suppressed and complete severity totals in the large profile.
No new warning/error or different diagnostic disposition appears across revisions.

Each import delivery produces 20 accepted representatives; the other 1,980 or
9,980 inputs are COALESCED. Receipts contain 20 accepted/public mutations and
zero rejections, canonical receipt outcome COMMITTED and durable terminal
SUCCEEDED. Stage and receipt warnings are both empty. Source labels remain
Feed Alpha/Feed Beta as admitted; canonical provenance is one occurrence per
winner under `dataframe-import:local-hosts`. All participants still stage.

### Separate computation counters: before / after

These diagnostic counts exclude startup/warm-up. Instrumented time/allocation
must not replace primary measurements.

| Counter | Document 100k occurrences | Import 10k rows |
|---|---:|---:|
| Actual classifications | 200,000 / 149,902 | 20,000 / 20,000 |
| Original classifications | 100,000 / 99,882 | 10,000 / 10,000 |
| Derived classifications | 100,000 / 50,020 | 10,000 / 10,000 |
| Host computations | 100,000 / 99,646 | 10,000 / 10,000 |
| Parser calls | 300,000 / 249,548 | 40,000 / 40,000 |
| Mapped candidates | 350,000 / 350,000 | 10,000 / 10,000 |
| Mapped cells | 2,100,000 / 2,100,000 | 100,000 / 100,000 |
| Template sends | 200,000 / 200,000 | 20,000 / 20,000 |
| Static argument splits | 400,000 / 0 | 10,000 / 0 |

The result confirms the review concern: distinct original URLs rapidly exhaust
bounded admission, so original classification/host derivation barely reuse.
Derived host classifications reuse for admitted source-dependent entries, but
later source contexts cannot obtain new entries once the joint budget is full;
there is deliberately no eviction. This is a cost limitation, not changed
acceptance or source semantics. Import row-local sessions cannot reuse across
CSV rows; URL original and derived host are distinct classifications even within
one row. Mapping/keys remain occurrence-proportional and optimized document
accumulators retain at most 20 winners per artifact. No cache expansion, early
deduplication, framework replacement or production optimization is introduced.

## Quality and disposition

The new driver has 13 offline Python contracts, including host/IP URL shapes,
source/repetition cardinality, disjoint warm-up identities, host-view configuration,
independent key/source/provenance rejection and selected-only statistics without
fabricated compatible ratios. The physical Spring smoke and every measured fork
exercise actual components. The complete tools contract suite passed before the
profile commit and again on the completed worktree. `make verify` and
`make pmd-analysis` passed on that worktree, followed by reviewed raw reports:

- Test lifecycle: 220 fast, 72 integration and 5 external suites; 287 deterministic
  offline suites. Provisioned external shells remain skipped offline.
- Coverage: 24,362/26,777 lines (90.98%), 8,506/10,291 branches (82.65%);
  all local/aggregate floors and missed-count ratchets pass.
- Raw SpotBugs: 120 accepted, zero visible; CPD: 24/24 groups; adopted PMD:
  zero blocking, 24/24 advisory. No analyzer drift, suppression, floor or inventory
  change was introduced. This extension changes no production Java or Java tests.
- Tools/packaging contracts pass, with 13 Python comparison contracts; local
  documentation link check reports zero errors.

Logs are `.dev/o7-cleanup-verify.log`, `.dev/o7-cleanup-pmd.log`,
`.dev/o7-cleanup-final-lint.log` and `.dev/o7-cleanup-final-docs.log`. Final committed
HEAD is checked again with `.dev/o7-cleanup-final-verify.log` /
`.dev/o7-cleanup-final-pmd.log` and `make context`. These deterministic gates do
not establish resource acceptance or external deployment performance. Resource
budget and complete concurrent application qualification remain separate.
