# CAP-7B routing execution qualification

Status: native sequential execution selected and implemented; final packaged
screen and repository gates pending. This is the conditional decision from the
[capacity plan](data-processing-capacity-plan.md), not G6 acceptance.
Entry production HEAD: `58359ddd6db12a8d416e16fbd61cec435500aafc`.

## Promotion contract

Retain configuration, demanded views, FIRST/ALL/EXCLUSIVE, short-circuit,
fallback diagnostics, ordered replies, trace, branch isolation and shutdown.
Native consumer UnitOfWork completion and stopped-route behavior are supported
contracts, already checked by the runtime conformance suites. Promotion needs
equal complete-service output and demonstrated net service benefit with simpler
ownership. There is no second selectable engine, runtime fallback, batch-wide
transaction or weaker capacity budget.

The existing document preparation and managed import use real disk cursors;
the experiment must not replace them with materialized lists or deduplicate
occurrences before validation/provenance. Canonical transactions, receipts,
recovery, admission and projection retain their established owners.

## Isolated controls

- [Direct processor control](qualification/capacity/cap-7b-direct-prototype.patch)
  calls the same compiled local processors without template/recipient dispatch.
  It fails existing native UnitOfWork and stopped-route conformance tests.
  It is rejected before performance promotion; IOC CSV equivalence cannot
  establish a legal replacement. No speedup is claimed for this control.
- [Native sequential control](qualification/capacity/cap-7b-native-sequential-prototype.patch)
  sends selected branches through the existing bound ProducerTemplate instead
  of the extra multi-recipient dispatch exchange. All 55 existing compiler,
  routing, recovery, lifecycle and aggregation conformance tests pass. It keeps
  the native view and branch consumers; demand and trace logic are unchanged.
  Compiler/dispatch cleanup is deliberately excluded from this timing control.

Both patches are evidence artifacts, never applied to the production worktree.
`processing-optimization-comparison.py --prototype-patch` compiles them in a
disposable copy of a frozen reference. Setup/matrix errors retain failure
evidence; temporary classes and runtime copies are removed. Inputs, probes,
external libraries, schemas, durability and policy remain equal between pairs.

## Measurement corrections and incomplete evidence

The first diagnostic setup failed because the observer completed a canonical
ownership timer on the separate validation transaction introduced by CAP-4.
Commit `a10cbec4` binds that timer only to methods acquiring active write
ownership. Its regression checks a real commit callback without ownership;
the corrected document/import diagnostic smoke passes. Failed samples are not
valid cost measurements.

One unique-input import fork failed the service ledger's
`updated_at_ms >= created_at_ms` CHECK. Log timestamps move backwards by about
two seconds across admission/staging/retry. The series remains incomplete;
successful earlier pairs do not turn it into a passing matrix. ING-16 retains
the coordination-clock risk separately from Router optimization.

## Paired complete-service evidence

[Compact raw evidence](qualification/capacity/cap-7b-reference.json) retains
all six fresh complete-service samples, configuration/input/runtime identities,
three paired ratios and output/cleanup results. Each runs a physical 100k HTML
mostly-unique document through the actual Spring/YAML/preflight/runtime,
private cursors, canonical writes, mutable projection and three immutable export
profiles. All five artifact/provenance oracles pass. Private source/workspace,
service/dataframe DBs and runtime state are removed; all processes terminate.

The policy is derived from the same stand configuration, with only private
paths/port, local sync exclusion and phase logging. Both paths have the same
2-CPU cgroup, 128/512 MiB JVM heap and 768 MiB/1 GiB cgroup high/max settings.
The reference boot JAR is `0314687cfa6f6278af7696ec3c08c086005ac4754fba72e59c9567dea899383c`;
the isolated native prototype is `e2242a33651d0d96cc4e996439364b4001a96f04fb80729cf712038ab3b505c6`.
Source identities in raw reports span harness/documentation commits only;
executable digests, input hashes and unchanged driver identities determine
which code was actually measured.

| Complete-service metric | Reference median | Native prototype median | Ratio of medians |
|---|---:|---:|---:|
| Conservative local window | 62.764 s | 60.043 s | 0.957 |
| Process CPU | 54.460 s | 52.200 s | 0.959 |
| Sampled RSS peak | 388,268,032 B | 382,726,144 B | 0.986 |
| Sampled heap-used peak | 89,723,187 B | 88,567,193 B | 0.987 |

The local window is an upper bound including scheduling/export waits after
subtracting configured stability; it is not a precise isolated Tlocal timer.
The paired time gains are 12.3%, 2.1% and 0.9%. Their median is about 2.1%,
which differs from the 4.3% ratio-of-medians gain. Three pairs do not establish
a general throughput promise or confidence interval. Sampled heap includes
garbage; cgroup file cache is not JVM heap, and no all-thread allocation total
is inferred from RSS or CPU.

Every sample deliberately retains `status=FAIL`: unchanged local-time and
five-second writer-occupancy budgets fail despite successful output checks.
G6 remains NOT_ACCEPTED. Million-row completion, sustained mixed load,
live SMB and activation are not qualified by these local samples.

## Paired document/import mechanisms

Three alternating pairs per kind pass equivalent results/configuration and
cleanup on the repeat profile (8,000 document occurrences or 2,000 import rows,
20 final keys). The document time ratio is 0.907 and caller allocation ratio
0.908; import ratios are 0.983 and 0.989 respectively. Import timings overlap
and one pair is slower. Startup is separate, not part of these execution timers;
caller allocation excludes asynchronous/background threads. These measurements
run real Spring components and disk cursors, not a neutral synthetic selector.

Three unique-domain document pairs complete (8,000 keys): median time changes
3,442.855 to 3,400.040 ms and caller allocations 2,212,474,920 to 2,180,424,752 B.
The related import matrix is incomplete after the UTC regression described above.
It is retained as failed evidence, not repeated until green or included in a
passing full-profile claim. Setup-only failures (compiler launch/release symbols
and JAR matching) have no valid timing samples and are listed in the JSON.

## Production decision and ownership

Admit only the native sequential simplification, formalized by
[ADR 0040](../../../ADR/0040-native-sequential-preparation-dispatch.md).
It has modest positive whole-service timing/CPU evidence with fewer mechanisms,
while retaining native lifecycle contracts. Remove the generated dispatch route,
compiled dispatch URI, recipient property, DispatchRequest and reply aggregation
strategy. Keep the same context, bound endpoints and reused ProducerTemplate;
freeze ordered typed replies at the existing result boundary. Selection and all
required-view resolution finish before any branch dispatch. Unexpected exceptions
stop the call; expected filtered/unavailable replies retain their prior semantics.

No direct evaluator, selectable engine, fallback runtime, shared batch UnitOfWork,
new core cursor port or speculative buffering is introduced. Workspace reduction,
validation/provenance, canonical transaction/receipt/recovery, writer admission
and projection keep their existing owners. The compiler endpoint inventory now
contains only view and branch routes. A new concurrent 64-branch regression
checks ordering, immutable replies, distinct exchanges/properties and native
completion across two callers. The obsolete private aggregation-strategy suite
is removed; the legitimate suite inventory changes 223 to 222 fast suites,
without lowering any coverage or analyzer floor.

Prototype timing excludes this final compiler cleanup. The final packaged-service
screen is recorded separately; no prototype ratio is relabelled as an exact
final-implementation performance result. CAP-7C atomicity/writer occupancy and
CAP-7D reducer capacity remain required separate work.

## Final verification

Pending final packaged screen and exact-HEAD repository gates.
