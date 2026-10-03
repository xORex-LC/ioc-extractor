# O5–O6 optimization implementation

Status: O5 implemented and measured; O6 prototype rejected. Final committed-HEAD
quality verification follows this evidence commit; O7 remains open.
Starting reference: e3b9248c, branch module/platform/router, 2026-10-03.
Scope follows [the optimization plan](processing-optimization-plan.md) and
[Camel applicability](camel-optimization-applicability.md). O7 acceptance is separate.

## O5 ownership and static metadata

RouterProcessedImportRowPreparer now receives the immutable compiled contract at
construction. It validates artifact/input/output authority once, indexes artifact
definitions and binds effective output merge policies. Each call still rejects
contract drift (including the same ID with a changed definition/fingerprint),
unknown admitted branches and missing input cells. Related branches, ABSENT/NULL,
conditional source authority, carrier clearing, compound conflicts, primary
output requirements and warnings retain their existing owners and semantics.

The compiler captures registered operation/destination processor references.
Selector trace outcomes use constant labels instead of per-decision lowercase
conversion; observer exceptions remain isolated by RoutingTraceSink. MDC remains
active even when decision tracing is disabled. The existing generic MdcScope
restores through its sequenced map's reversed entries, removing close-time
stream/list construction without adding a second MDC implementation.

The retained O4 diagnostic document recording attributes sampled allocation
weights to executeAdmitted (107.9 MB), PlanSelection construction (76.8 MB),
MdcScope.put (7.9 MB) and MdcScope.close (5.2 MB). These are sampling weights from
an instrumented recording, including incidental setup; not exact allocation
counts or performance acceptance. Mapper/provider/transform metadata binding and
lazy failure containers are deferred: this profile does not establish a useful
isolated gain for them. Both paths retain the single ConfigurableRowMapper.

Focused regressions cover full-contract drift, per-row admission, static authority,
compound carrier and source contracts, nested MDC, repeated writes, unowned keys,
exception restoration and Camel trace/concurrency contracts. No dependency,
module, durable schema, policy or configuration knob changes are introduced.

## Isolated MDC mechanism check

Three alternating before/after fresh JVM pairs exercise 20,000 warm-up and
100,000 measured three-key scopes (`-Xms128m -Xmx512m`), checking the in-scope
view and ambient restoration. Before uses the e3b9248c compiled MdcScope; after
uses the source hash and standalone source retained in
[the isolated report](qualification/optimization/o5-mdc-isolated.json).
Median calling-thread allocation is 53,990,184 → 36,766,712 bytes (31.9% lower)
and elapsed time 54.6 → 41.6 ms. This qualifies removal of the close-time list
in that mechanism, not an application speedup or a whole-process memory claim.
Raw logs and the reproducible source remain under .dev/o5-mdc-probe and
.dev/O5MdcProbe.java; the report embeds the source independently of those files.

## Qualification

Primary comparisons, diagnostic counters and final quality evidence will be
recorded below before this slice is considered qualified. Raw recordings and
failed experiments stay under ignored .dev workspaces.

O5 checks: focused import, Camel and MDC tests passed; `make verify`,
`make pmd-analysis`, `make pmd-watchlist` and `make docs` passed. The adopted
PMD pass followed removal of two newly unused private method parameters.
Raw SpotBugs (120 accepted), CPD (24), PMD policy (24) and watchlist (30)
reports were reviewed; no new finding or ratchet adjustment was introduced.
The final combined slice will be verified again on its committed HEAD.

## O6 experiment admission criteria (before candidate measurements)

The isolated candidate keeps generated view and branch routes, binds each view's
native consumer pipeline after context startup, and uses one plan-entry
ProducerTemplate request per invocation. It retains a caller-owned invocation
frame and native sequential Recipient List copies for selected branches,
including one recipient. This is one producer entry, not one physical Exchange.
Zero recipients now enter Camel, and a single recipient gains a dispatch copy;
both are explicit potential costs to measure rather than hidden assumptions.

Promotion requires unchanged conformance, native view UnitOfWork completion and
failure cleanup, branch isolation and production document/import tests. Before
examining candidate timing, a useful primary gain is defined as at least 10%
less selected allocation or elapsed time on a repeat-heavy workload, with no
more than 10% selected allocation regression on the other workload. Timing
changes must exceed the paired spread; startup/unique/concurrent and sampled
memory changes require investigation at a 15% regression. The original resource
envelope remains unchanged. Failure to meet this local promotion screen removes
the prototype and retains O1–O5; it does not authorize changing O7 acceptance.

## O5 primary comparison

Five alternating fresh-JVM pairs per workload, one disjoint warm-up, identical
8,000/20-key document and 2,000/20-key import fixtures, logging and JVM settings.
Before is the retained O4 production reference 2ddae036 (production-equivalent
at starting e3b9248c); after is clean 7e0330bd. All input digests, per-path
configuration digests, outcome counts and canonical/projection signatures match
across revisions. Reviewed report: [O5 warmed comparison](qualification/optimization/o5-after-warm.json).

| Selected workload | Median time O4 / O5 | Calling-thread allocation O4 / O5 | Sampled heap O4 / O5 | Current RSS O4 / O5 |
|---|---:|---:|---:|---:|
| Document | 399.5 / 376.5 ms | 256.1 / 243.7 MB | 120.3 / 120.5 MB | 386,864 / 374,608 KiB |
| Import | 595.7 / 559.5 ms | 81.3 / 76.1 MB | 118.7 / 117.8 MB | 396,296 / 396,480 KiB |

Allocation decreases 4.8% and 6.4% respectively. Timing falls approximately 6%
but compatible timing also shifts; these independent runs do not establish a
universal latency improvement. Whole-process memory is effectively unchanged.
O5 selected/compatible caller allocation remains 2.117 for documents and 1.217
for import; the historical envelope passes but O7/customer acceptance is open.

## O6 result: do not promote the one-entry prototype

Candidate 513c489e applies the [retained patch](qualification/optimization/o6-one-entry-prototype.patch)
to clean O5 reference 7e0330bd. It was built and exercised in an isolated worktree;
none of its production changes are included in the working branch. The existing
57 Camel tests (including added UnitOfWork/isolation coverage) and integration
module command passed. Production `CustomerRoutingPipelineIT` and
`RouterSelectedImportDeliveryIT` passed unchanged, including YAML/preflight,
actual preparation operations, canonical commit, COALESCE and receipt recovery.
The stopped-route regression also passes on the retained O5 runtime.

Five alternating primary pairs per workload use the same harness, input/config
hashes, logging/JVM settings and one disjoint warm-up as O5. Every same-path
outcome and canonical/projection signature matches across revisions. Report:
[O6 warmed prototype comparison](qualification/optimization/o6-prototype-warm.json).

| Selected workload | Median time O5 / prototype | Calling-thread allocation O5 / prototype | Sampled heap O5 / prototype | Current RSS O5 / prototype |
|---|---:|---:|---:|---:|
| Document | 376.5 / 380.3 ms | 243.7 / 243.0 MB | 120.5 / 120.4 MB | 374,608 / 383,860 KiB |
| Import | 559.5 / 610.4 ms | 76.1 / 81.6 MB | 117.8 / 117.9 MB | 396,480 / 389,564 KiB |

Document allocation changes -0.27%, elapsed time +1.01%; import allocation
+7.28%, elapsed time +9.09%. Startup medians increase 8.64% and 4.14%.
Sampled memory is effectively unchanged, with overlapping RSS ranges.
The historical compatible/selected envelope passes; the predeclared O6 promotion
screen fails because neither workload gains 10% in allocation or elapsed time.
There is no reason to spend the remaining unique/cold/concurrent performance
qualification budget promoting this candidate. Those performance profiles were
not run for it. Lifecycle/concurrency *correctness* tests did run.

### Complexity and ownership inventory

| Concern | O5 | Prototype |
|---|---|---|
| Generated routes | one per operation view, one per branch, one dispatch | same count; entry replaces dispatch |
| ProducerTemplate calls, measured plans | host view + destination/dispatch | one plan entry |
| View execution | bound endpoint template send | bound native consumer processor with fresh child Exchange |
| Branch execution | single bound send or sequential Recipient List | sequential Recipient List, even for one recipient |
| Invocation state | InvocationViews plus selection/blocked locals | same state plus caller-owned PlanCall frame |
| New binding state | bound endpoints | bound endpoints plus LocalViewRoute per operation view |
| Selection/recovery mechanisms | CompiledSelector / InvocationViews | unchanged; moved into entry callback |
| Resource lifecycle | context/template and route-managed UnitOfWork | same owners plus explicit consumer Exchange release wrapper |
| Exception seam | Java preparation exceptions propagate directly | frame retains preparation failure to undo Camel wrapping |

The prototype adds 93 net production lines and two internal types while removing
nested template sends; it does not eliminate view child Exchanges or custom
three-state selection/recovery. Native consumer pipelines preserve route-owned
UnitOfWork completion; the wrapper explicitly releases its child Exchange after
reading the typed outcome. This uses the pinned
[Consumer API](https://raw.githubusercontent.com/apache/camel/camel-4.22.1/core/camel-api/src/main/java/org/apache/camel/Consumer.java)
and the same processor invocation used by
[DirectProducer](https://raw.githubusercontent.com/apache/camel/camel-4.22.1/components/camel-direct/src/main/java/org/apache/camel/component/direct/DirectProducer.java).

Single-recipient import gains an entry/Recipient List boundary that offsets
removing its view template request. Empty selection also gains a Camel entry.
Tests preserve complete preselection before dispatch, lazy shared views,
FIRST/EXCLUSIVE blockage, unexpected exceptions without fallback/redelivery,
recovery severity, ordered replies, immutable input, native completion/failure
callbacks and header/property isolation. The completion/isolation test and stopped-route assertion remain
in the adopted branch; the production prototype is removed from consideration.

This rejects this concrete execution layout, not every possible Camel compiler
specialization. Inlining all operations into one shared mutable Exchange was
not tested: proving restoration, independent completion scopes and branch
isolation would be a separate experiment. No second runtime, feature toggle,
new dependency, schema migration or superseding architecture decision is added.

### Reproduce the rejected candidate

From a clean repository root, create an isolated checkout at 7e0330bd, apply the
retained zero-context patch (only on that exact base), commit that experimental snapshot, and run the same facade:

```bash
git worktree add --detach .dev/o6-reproduce 7e0330bd
git -C .dev/o6-reproduce apply --unidiff-zero ../../docs/worknote/0.3.0/data-processing-routing/qualification/optimization/o6-one-entry-prototype.patch
git -C .dev/o6-reproduce add adapters/adapter-processing-camel/src
git -C .dev/o6-reproduce commit -m 'experiment: reproduce rejected O6 layout'
cd .dev/o6-reproduce
make test-module MODULE=adapters/adapter-processing-camel
make test-integration-module MODULE=bootstrap/ioc-app
DEBUG=false LOGGING_LEVEL_ROOT=WARN make processing-route-comparison \
  COMPARISON_ARGS='--pairs 5 --warmups 1 --workspace .dev/o6-reproduce-warm'
```

Raw logs remain under ignored .dev workspaces; primary reports and source patch
are retained above. No primary comparison ran alongside another Maven/test job.
The rejected candidate has no whole-reactor release qualification; production
quality checks apply to the retained implementation, not to an unadopted engine.

### Separate mechanism counters

One diagnostic pair per workload uses the unchanged observer/JFR harness on the
same clean candidate: [diagnostic report](qualification/optimization/o6-prototype-diagnostic.json).
Timing and allocation in this instrumented run are not primary evidence.
Template sends are 8,000 for 8,000 document observations and 2,000 for 2,000
import rows, versus 16,000 and 4,000 in the retained
[O4 diagnostic reference](qualification/optimization/o4-after-diagnostic.json).
O5 does not change those send sites. All other counted semantic work agrees:
24,000 document candidates, 136,000 mapped cells, 20 host computations,
60 retained output rows and 20 reserved IDs; import retains 2,000 prepared rows,
20,000 mapped cells and 2,000 host computations. View demands/evaluations,
predicates, classification and winner updates also agree. Missing view-template
send counters mean no such call site, not skipped view evaluation.

Thus fewer producer entry boundaries alone did not reduce real preparation
cost enough. The candidate keeps view Exchanges and gains a single-recipient
fan-out. The evidence supports retaining the existing specialization instead
of introducing the extra frame/resource/exception machinery without a gain.
