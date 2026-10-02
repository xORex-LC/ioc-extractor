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

## O1 — invariant binding

Runtime-owned endpoints are resolved once after context startup. Descriptor
URIs remain neutral; only the adapter stores Camel endpoint references and
uses them for view requests, dispatch and recipient selection. Existing producer
reuse, admission and bounded close behavior remain in place.

Predicate registrations now expose a startup factory. Existing argument-aware
bindings adapt to that seam; each condition leaf binds once before runtime
startup. The IOC `type-in` factory builds an immutable type set. Eager semantic
preflight still validates configured types before compiling any route.
No observation results or mutable invocation data are cached.

Added regressions cover binding once with repeated reached evaluations,
factory failure before startup, endpoint binding failure cleanup and two
independent runtime contexts. Existing routing, recovery, concurrency, close and
invalid-configuration suites remain part of qualification.

O0 baseline on clean `52517ceb` used five pairs for each retained 8k-document /
2k-import workload. All output signatures matched. Selected/compatible ratios
were 1.938 time and 2.941 calling-thread allocation for the document; 1.564 and
1.560 for import. See [raw baseline samples](qualification/optimization/o0-baseline.json).
The historical guards passed; this is not an agreed acceptance budget.
Focused O1 adapter tests and O0 sampler regressions passed; complete gates are
recorded after the final O2 worktree is fixed.
