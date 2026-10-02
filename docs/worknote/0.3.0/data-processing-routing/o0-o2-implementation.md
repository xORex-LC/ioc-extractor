# O0–O2 implementation and qualification

Scope: the first three stages of the accepted
[optimization plan](processing-optimization-plan.md). Winner retention,
semantic caching and sessions (O3+) are not part of this change.

## O0 — comparable measurements

The production-composition probe now has explicit document/import cardinality,
independent alternating pairs (five by default), and optional disjoint warm-up
files in each JVM. Warm-up deliveries use fresh identities; the measured
workload remains an insertion, not receipt replay or a canonical no-op.

The harness records source identity before compilation and rejects edits during
compilation. All measured JVMs load a frozen copy of the bootable JAR's classes,
libraries, probe and test resources. Later Maven compilations cannot change a
running comparison. Config and input digests, runtime dependency names, JVM
flags, CPU/cgroup limits, pair order, startup, workload time, throughput,
calling-thread allocation, GC counters, sampled heap, current VmRSS and historical
VmHWM are retained. Sampler failure prevents publication.

Signatures compare canonical keys and IDs, source occurrence summaries and CSV
bytes for documents; final import fields/keys, COALESCE statuses and measured
canonical receipt counts for imports. Document outcome summaries preserve
complete severity counts and retained diagnostic code counts. Compatible
batch deduplication emits one skipped-item warning per duplicate; selected
final-key reduction does not. These known counts are checked independently,
not asserted equal between different processing policies. Same-path outcomes
remain available for cross-revision checks.

The initial pre-checkpoint measurements under `.dev/processing-o0-baseline` and
`.dev/processing-o1-endpoints` were exploratory: sources changed while those
experiments were running. They are not isolated per-change qualification.
Committed-revision comparisons use separate worktrees and the same harness.
The warmed 32/24-row smoke preserved every compared outcome. Its single-pair
heap ratio was 1.70, outside the historical 1.25 guard; the failed report is
retained. This is a wiring check, not performance acceptance, and no threshold
was relaxed. A first focused sampler test also exposed interruptible NIO
reading before the sleep; an explicit interrupt check preserves the sampler
failure contract.

Existing 2x time / 3x allocation / 1.25x memory / 1 GiB RSS limits remain
historical regression guards. Customer acceptance limits are still unresolved;
O0–O2 do not close O7 performance acceptance. The wider workload matrix and
longer diagnostic profiling remain required for final qualification.
