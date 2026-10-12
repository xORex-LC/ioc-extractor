# Bounded projection ACK/error experiment

Status: bounded experiment completed, 2026-10-11. Correctness passed; activation
is not admitted and resource-budget acceptance remains open. This is the
first bounded candidate from [the hybrid assessment](sqlite-hybrid-storage-assessment.md),
not a general hybrid dispatcher or production-backend activation.

## Implementation and boundaries

`JdbcProjectionMutation` owns the existing SQL compare-and-set policy for
native, privately prepared and direct execution. Direct execution obtains the
existing control admission and canonical writer before reading committed
projection state. One owned TEMP row on that borrowed connection carries the
transition, including `requested_at_ms`, NULL error/timestamp fields and the
existing required/projected generations. A changed row becomes one typed
effect through the existing begin/append/seal/publish protocol in the same
transaction. The common protocol validates dependencies, pins/fence and safe
publication UTC. Commit/rollback and connection ownership stay with
`JdbcPublicationTransactions`; no private DB, pool, migration or intermediate
committed hidden state is created for this operation.

An unchanged transition still validates safe UTC and interruption, but creates
no publication unit or artifact epoch change. Successful return follows commit.
A lost response is handled by retrying the existing monotonic generation CAS;
the port does not supply a durable command identity. A retry at a later wall
time can update its operational `projected_at_ms`, as in the existing policy;
it cannot confirm coverage beyond its installed generation.

Admission is restricted to projection ACK/error for one configured artifact.
The typed chunk byte limit bounds the one effect; pre-read SQLite byte lengths
reject oversized existing payload before Java materialization. A 2048-byte
framing reserve and conservative `3 * String.length()` bound reject oversized
new failure tokens before mutation. Rejection has no unbounded fallback.
Required generation, operational ACK/error, artifact epoch granularity,
versioned representation, journal and readers remain unchanged. An effective
ACK on the same artifact still invalidates a prepared promotion and can cause
one of its existing bounded rebuilds. A same-value/timestamp no-op does not.

The normal staged projection store keeps private preparation. Only the test
launcher accepts `qualification.projection=prepared|direct`; the production
`ioc.*` configuration and native default do not change.

## Correctness qualification

`DirectProjectionControlIT` uses real file-backed SQLite databases, the native
mutation kernel, shared typed protocol and bounded coordination. It covers:

- Shared ACK/error CAS semantics, future/stale generations, error clearing,
  same-value no-op and generation advancement.
- Prepared document or cross-artifact import → direct ACK → publication:
  dependency conflict/rebuild, preserved ACK, complete canonical data/receipt,
  and receipt replay after deleting the sealed import stage.
- An unrelated artifact and an unchanged control do not invalidate a prepared
  document; writer ownership excludes a competing publication before commit.
- Rollback after typed append and publication marking, interruption before
  commit, safe-clock rejection even for no-op, scope/input/byte admission,
  corrupt or absent metadata, and TEMP cleanup on every pooled connection.
- Forced child-process loss during typed append, immediately before commit
  and after commit before successful return: reopen sees all or none, and CAS
  retry preserves the correct durable outcome.

The reviewed suite universe grows by one deterministic integration class:
222 fast, 81 integration including five external shells, 298 deterministic.
Coverage floors and analyzer baselines are not relaxed.

## Cost qualification method

`make projection-ack-comparison ACK_COMPARISON_ARGS='--baseline-jar <frozen-old-jar>
--jar <candidate-jar> --config <policy> --output .dev/<new-root> --rows 10000
--samples 3 --burst 200'` freezes both application JARs and the same test-only
Spring launcher/admission agent. The collector records JAR, source-worktree,
helper-class, policy and driver identities. A dirty worktree is identified by
its complete content digest; it is never reported as an exact committed build.

Both modes use the normal configuration/preflight, real extractor/classifier,
Camel, ingestion/import, SQLite canonical/service files, projections and local
immutable slices. Existing independent disk oracles validate final public
fields, complete keys, provenance, canonical identities and export slots;
receipt/family census detects orphan evidence. Paired inputs and independent
logical results must agree before a successful ratio is reported.

Each fork first executes 200 actual ACKs after the same warmup/population,
using a one-millisecond delay in both modes to avoid accidental same-timestamp
no-ops. It retains every ACK/read latency, whole-JVM allocations and exact
canonical admission totals/maxima, sampled heap/current RSS, family counts,
WAL/workspace/main-file bytes and the pinned-driver read plan.

Then it processes a receipt-heavy unique DOCX or a selected processed CSV
whose host view collapses many URL paths into 20 final keys. One-second
parallel control probes ACK pending generations; otherwise they exercise the
port's legitimate stale-error rejection. They do not repeatedly re-ACK an
already installed generation during preparation. Such repeated successful
timestamp changes are deliberately covered as a conflict in forced-order
tests rather than presented as normal projection scheduling. Existing
retention/version maintenance runs every five probes; a declared ten-page
catch-up follows the measured publication window. Raw failed windows are
retained without retry; a failed pair stops further pairs in its profile.

Read p95/p99 use nearest rank over retained per-operation samples, not three
workload medians. Burst memory maxima are observations at ACK boundaries,
not continuous peaks; the workload sampler separately records JVM/cgroup
resources. Named-file accounting excludes unlinked SQLite TEMP files.
One drained short run is not evidence of sustainable cleanup or broad
active/match/hydration/export read p99 on a populated production database.
Resource-budget acceptance remains open.

## Results and disposition

The direct candidate substantially reduces fixed ACK preparation cost. It does
not reduce version/journal effects, remove artifact-epoch conflicts, establish
sustainable cleanup or demonstrate a universal whole-service speedup. The
document window is slower in every pair. Keep native as the production default
and prepared execution as the normal staged composition. This candidate remains
qualification-only; no automatic corrective cycle follows.

The `qualification/capacity/projection-ack/comparison.json`
retains twelve successful forks, six complete pairs, 2,400 ACK samples, exact
publication counters, receipts/oracles, file sizes and frozen identities.
The `qualification/capacity/projection-ack/summary.json` retains
per-fork values, medians/ranges, paired ratios and a raw-file manifest. The
[evidence directory](qualification/capacity/projection-ack/README.md) retains
clock/resource/concurrent-control JSONL. No qualification window failed or
was retried.

### Measurement identity

| Item | Recorded identity |
|---|---|
| Source HEAD | `46cb4ab3b1347c3fa713e9748e2f28c63bcacac3` |
| Measured dirty-worktree SHA-256 | `b7034e14d44d1dd2002731edebacf98eddb2d1d5940b7d03f3a595be46cf72e7` |
| Frozen old JAR SHA-256 | `1434fdb9e6740b479bdf92c80564814ce68c6ab47bb6cc34171f54f2fb03f455` |
| Frozen candidate JAR SHA-256 | `023d53897b1eb574f107c4dbaaefe51b3da313d141850ccd0dba00078d275853` |
| Original policy SHA-256 | `5114905f428ef4f22dcb9f732568373562738562a7775158053c154c2f71c5d9` |
| Measured collector SHA-256 | `d730aca3686f74f3974603759245e94aa1e480f99c08e82ed9987e96b1c1b79c` |
| JVM | OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`, `-Xms128m -Xmx512m` |
| Effective cgroup | two CPU quota, `memory.high=768 MiB`, `memory.max=1024 MiB` |
| UTC preflight | 900 samples / 90 seconds, `OBSERVED_STABLE`; runtime checks retained |

Both executables use the same frozen test helpers; individual class hashes are
in the report. The old JAR was copied before modifications and contains no
direct class; it does not embed Git-commit proof. The worktree digest, not HEAD
alone, identifies candidate sources.

After measurement, the collector's temporary-directory flag was corrected to
observe cleanup after its owner exits; bytecode-file generation was disabled.
No timing/resource metric changed. The raw `temporary_runtime_removed=false`
is preserved: the original check ran too early. Independent audit confirmed
all twelve processes and private sample directories removed, zero cleanup
errors and no owned runtime directory left. A regression covers cleanup on
successful and exceptional exit.

### Controls alone

Medians of three forks per profile/mode follow. Each fork executes 200 actual
ACKs, with the same 1 ms delay, two committed-state reads per probe and JVM
instrumentation. Delay/bookkeeping remain included. MiB is 1,048,576 bytes.

| Metric | DOCX prepared | DOCX direct | Import prepared | Import direct |
|---|---:|---:|---:|---:|
| 200 ACK series, seconds | 18.728 | 0.697 | 18.793 | 0.718 |
| ACK p50, ms | 90.327 | 1.784 | 90.833 | 1.859 |
| ACK p95, ms | 104.927 | 3.352 | 105.678 | 3.388 |
| ACK p99, ms | 113.974 | 6.461 | 112.171 | 6.773 |
| Whole-JVM allocations, MiB | 1,483.33 | 30.06 | 1,484.01 | 30.03 |
| CONTROL writer admissions | 1,000 | 200 | 1,000 | 200 |
| Total writer hold, ms | 601.19 | 293.46 | 617.82 | 310.38 |
| Maximum writer hold, ms | 6.40 | 8.69 | 7.09 | 6.98 |
| Total writer wait, ms | 8.10 | 2.34 | 8.47 | 2.10 |
| Projection-read p95, ms | 0.369 | 0.343 | 0.359 | 0.332 |
| Projection-read p99, ms | 0.427 | 0.491 | 0.432 | 0.388 |
| Heap sampled after ACK, MiB | 87.78 | 87.79 | 89.42 | 89.26 |
| Current RSS sampled after ACK, MiB | 358.77 | 348.93 | 369.96 | 350.52 |

Pooling 1,200 retained operations per variant gives prepared/direct ACK p95
**104.893/3.430 ms**, p99 **113.748/6.773 ms**. These are nearest-rank
observations, not confidence bounds or accepted SLOs. ACK allocations fall
about 98%, series time about 96%, admissions from five to one. Total writer
hold approximately halves, but worst single hold is not uniformly lower.
Disk stalls, checkpoints and scheduling remain outside the byte/row proof.

Pooled projection-read p99 is **0.432/0.453 ms**; fork p99 ranges are
0.387–0.489 ms prepared and 0.357–0.496 ms direct. This narrow one-artifact
lookup demonstrates no read improvement. Broad production read tails remain
unqualified. The pinned-driver plan retains the same version lookup/address
indexes and committed-parent visibility checks.

### Local workloads with concurrent controls

Profiles are 10,000 mostly unique DOCX occurrences across six IOC types and
10,000 selected processed-import URLs collapsed by the host branch into
twenty new mask keys. Warmup/population contribute equal pre-existing rows.
No numeric resource threshold is applied.

| Metric, median of three forks | DOCX prepared | DOCX direct | Import prepared | Import direct |
|---|---:|---:|---:|---:|
| Local window excluding document quiet time, s | 18.606 | 21.734 | 7.724 | 6.886 |
| Window range, s | 17.991–19.797 | 21.718–21.793 | 7.718–7.812 | 6.797–6.891 |
| Input occurrences/rows per second | 537.5 | 460.1 | 1,294.7 | 1,452.2 |
| Whole-JVM allocations in publication window, MiB | 3,582.76 | 3,461.00 | 469.91 | 411.72 |
| Sampled heap peak, MiB | 86.03 | 85.37 | 84.80 | 85.98 |
| Sampled current RSS peak, MiB | 387.77 | 381.04 | 381.67 | 375.91 |
| Sampled cgroup peak including file cache, MiB | 585.02 | 575.65 | 385.83 | 380.64 |
| Largest eligible concurrent control, ms | 426.64 | 80.66 | 134.77 | 2.50 |
| Publication rebuild counts by fork | 1 / 1 / 1 | 1 / 2 / 2 | 0 / 0 / 0 | 0 / 0 / 0 |

Document direct/prepared time ratios: **1.171, 1.207, 1.098**; allocations:
**0.966, 0.965, 0.969**. Import time ratios: **0.892, 0.870, 0.892**;
allocations: **0.875, 0.874, 0.885**. The result is mixed.

The ACK prefix changes allocation, GC/JIT history and process age radically
before the bulk workload. Therefore the later document window is not a
controlled causal estimate of isolated bulk-kernel cost. Preserve its
regression; do not attribute it solely to one rebuild or omit it because CSV
bytes agree. Both paths retain the same three-attempt limit; maximum observed
attempt is two for documents and one for imports. Aggregate phases do not
identify each rebuild's cause.

For the deliberately ACK-heavy sequence, adding ACK-series elapsed and raw
handoff-to-slices elapsed gives **39.451 → 24.488 s** for DOCX and
**26.536 → 7.594 s** for import. Allocations in those two windows are
**4.947 → 3.409 GiB** and **1.908 → 0.431 GiB**. This sum includes document
quiet time but excludes startup, warmup, intervening setup and cleanup. It is
not end-to-end service latency or a forecast: normal traffic need not issue
200 timestamp-changing ACKs for one installed generation.

### Physical effects and cleanup

Each burst creates exactly **200 units, 200 attempts, 200 facts, 200 chunks
and 200 projection versions in both variants**. Direct execution changes
setup/admission, not representation or effect count.

| Metric, median of three forks | DOCX prepared | DOCX direct | Import prepared | Import direct |
|---|---:|---:|---:|---:|
| Named WAL peak, MiB | 47.31 | 40.35 | 10.98 | 12.14 |
| Named workspace peak, MiB | 56.88 | 56.88 | 12.74 | 13.79 |
| Canonical main file after catch-up, MiB | 148.238 | 148.203 | 3.875 | 3.852 |
| WAL after catch-up, MiB | 9.324 | 9.324 | 5.540 | 5.579 |
| Projection versions after publication | 230 | 230 | 222 | 222 |
| Projection versions after ten catch-up pages | 102 | 102 | 98 | 98 |

Sampler peaks begin after the ACK burst and include ten maintenance pages;
raw sample count is cumulative from startup. Allocation/publication counters
end before catch-up. These scopes do not constitute an exact publication-only
peak. Other daemon maintenance can advance between snapshots. Catch-up itself
creates control metadata, so rising unit count is not an ACK leak. Remaining
versions cannot all be labelled reclaimable debt: current, retained and pinned
visibility must be distinguished. Ten pages do not prove drain completion,
debt age or sustainable cleanup. No OOM/OOM-kill or memory-pressure event
occurred; CPU throttling remains recorded in raw samples.

### Correctness, recovery and quality

All twelve forks pass independent field, complete-key, provenance, mutable
projection and immutable-slice/slot checks. Document final counts including
population are masks/ip_list **1,694 each**, hashes **5,067**, blacklist
**3,388**, aggregate **10,144**. Import receipt has **20 accepted final rows,
zero rejected, 20 public mutations**, `COMMITTED` and `TERMINAL/SUCCEEDED`;
final mask count including population is **47**. COALESCE from 10,000 source
rows is checked. Every checked field-origin orphan count is zero.
Receipt-only recovery/crash boundaries are tested, not timed restart
measurements in this series.

- `DirectProjectionControlIT`: **21 cases, zero failures/errors/skips**,
  including forced ordering, JDBC/resource failures and three child-JVM loss
  boundaries. Real receipt recovery is checked with sealed stage absent.
- `make verify`: deterministic reactor, report/JaCoCo integrity, ArchUnit,
  SpotBugs and CPD. Coverage/analyzer floors are unchanged; only the reviewed
  integration suite-universe increment is applied.
- `make pmd-analysis` and `make pmd-watchlist`: policy and resource/exception
  ownership review. SpotBugs **168 accepted / zero visible**, CPD **23 groups**,
  adopted PMD **20 findings**, watchlist **80 findings**. None affects a new
  direct class or changed method; six factory watchlist findings are unchanged
  existing members.
- Collector: **40 passing offline contracts**, including failed-pair
  suppression, semantic admission, nearest-rank tails and directory cleanup.
  `make docs` and `git diff --check` validate links and whitespace.

Java/static gates are deterministic local evidence; twelve forks are
provisioned local-service evidence. Neither implies installed-stand, C5/G6 or
release-budget acceptance. Final command logs remain in `.dev/`.

## Remaining decisions

The experiment is finished. Activation requires a separate decision: narrow
ACK gain coexists with longer observed document windows and unchanged effect
cost. A future isolated activation study, if requested, should equalize
pre-workload conditioning and retain these observations. Scope narrowing,
typed-journal consolidation, append-only receipts, cross-file ACK ownership
and general dispatch remain outside this change. No automatic next stage is
admitted.
