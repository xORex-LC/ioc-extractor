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

## O2 — reply aggregation and copies

Each generated branch route validates and materializes its typed `BranchReply`
inside Camel, so a missing destination outcome aborts both dispatch paths with
the same cause. Multiple recipients use Camel 4.22.1's
`AbstractListAggregationStrategy<BranchReply>` with exchange-owned mutable
accumulation and one immutable snapshot in the normal processor after Recipient
List completion. Camel owns the completion callback itself: the regression
found that throwing while freezing a failed subexchange in that callback can
prevent its async completion notification. Failed calls now propagate through
the ordinary error path before the freezing processor. Null replies are rejected
before Camel can omit them. No mutable list is stored on the strategy instance.

Exactly one eligible recipient uses the bound branch endpoint directly, after
full selector evaluation and all selected required-view resolution. The same
public reply/result contract and tracing apply. FIRST never reselects;
EXCLUSIVE must still establish uniqueness; zero recipients finalize normally.

Regressions cover 64 ordered replies, interleaved invocation isolation, immutable
completed lists, original input isolation, typed prepared/filtered/unavailable
outcomes on both paths and missing destination outcomes. Existing recovery and
concurrent lifecycle tests exercise the completed runtime.

O1 clean `9936ade4` preserved every same-path signature from O0 across five
pairs per workload. Selected medians changed from 1340.4 to 1185.5 ms and
430.1 to 342.5 MB calling-thread allocation for the document; import changed
from 1274.0 to 1239.9 ms and 119.7 to 107.9 MB. Timing spread prevents treating
the small import time change as a firm conclusion. All historical guards
passed. See [O1 samples](qualification/optimization/o1-binding.json).
The final focused O2 dispatch test run passed after moving the immutable
snapshot out of Camel's completion callback; the failed timeout evidence is
retained locally and was not retried without a code change.

`ArtifactRow.ordered` now delegates directly to the record constructor's single
ordered defensive snapshot. Its public map still preserves null cells and
column order, rejects mutation and is isolated from subsequent caller-map
changes. A regression also checks that `withValue` leaves the original row
unchanged. This copy change is committed and measured separately from dispatch.

O2 dispatch on clean `c05e3d23` preserved all same-path signatures. Relative to
O1, calling-thread allocation changed by -0.6% for the three-recipient document
and -5.7% for the single-recipient import. Selected time medians were 1155.7 and
1083.6 ms. The small document change is not evidence of a large end-to-end gain
from list aggregation; the asymptotic benefit matters with larger fan-out.
See [dispatch samples](qualification/optimization/o2-dispatch.json).

Two failed isolated build attempts were retained locally before measurement:
new direct dependencies first needed parent version management, then
`camel-support` had to retain compile scope because the Camel DSL inherits its
builder classes. Both were fixed in separate build commits. They are not
successful measurement runs and no failed pair was dropped.
