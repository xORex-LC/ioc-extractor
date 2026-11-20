# com.iocextractor.adapter.processing.camel.runtime

## Purpose and structure

Own one isolated embedded Camel context and invocation-local evaluation state.
Follow the [module dependency rules](../../../../../../../../../README.md).

| File | Responsibility |
|---|---|
| [CamelRouteRuntime.java](CamelRouteRuntime.java) | Context/producer/endpoint lifecycle, active-call admission, readiness and ordered branch dispatch |
| [InvocationViews.java](InvocationViews.java) | Package-private per-call view cache, explicit recovery and demanded-consumer failure evidence |

## Construction and invocation

Construct the runtime from an admitted `CompiledRoutes` inventory. It adds routes,
starts the context, binds endpoint references belonging to that context and creates
one producer. Startup exceptions stop a partially created context and retain
shutdown failure as suppressed evidence. Default shutdown grace is five seconds;
a supplied timeout must be at least one second. There is no dynamic plan reload
or restart API.

`execute(planId, original)` admits a call unless closing, requires non-null input
and a known plan, and creates fresh `InvocationViews`. It completes selection
and resolves selected branches' required views before any destination call.
The first unavailable required view blocks that branch's preparation; ALL can
still dispatch independently prepared siblings. Selection is not rerun because
of a preparation block, filtered reply or expected destination failure.

The root `original` and demanded views are cached only for this invocation.
Available/absent/unavailable results and recovery decisions are computed once
when reached. An unavailable/absent ancestor prevents a dependent ordinary
operation from running. Unused views and unneeded recovery alternates do not run.
Only allowlisted expected unavailability enters recovery; unexpected exceptions
abort execution. A failed/absent alternate retains the primary and alternate
causal evidence. Recovered/unrecovered consumer lists follow actual branch
demands, not every branch that could have referenced a view.

## Dispatch, ownership and failures

Ready destinations receive the same `BranchInput` snapshot sequentially in
selection order through bound Endpoint references. Each view/branch request has
its own Exchange and native UnitOfWork. Mutable headers/properties are isolated
between requests. Original/resolved payload objects are shared references:
the runtime does not deep-copy them, make mutable user objects safe or clean up
arbitrary resources carried in them. Processors must respect the
[contract ownership rules](../contract/README.md).

Typed replies accumulate locally and become immutable on return. A thrown
processor/predicate/scope exception or missing typed result aborts the call;
there is no automatic retry or rollback. A later destination failure may follow
completed earlier destination effects without returning a partial result object.
Registered destinations should remain preparation-only; the outer IOC pipeline
owns diagnostics, failure-policy checkpoint and canonical persistence.

The default trace emission hook contains RuntimeException observer failures,
while Errors propagate. Branch reply traces are emitted after destination calls
finish; an aborted invocation is not guaranteed a trace for every completed
callback. Trace metadata is observation rather than delivery authority.

## Concurrency, readiness and close

Concurrent callers share the context/producer and compiled callbacks, while
views, recovery evidence and replies remain per call. Bound predicates,
processors, scopes and observers must be reusable/thread-safe. There is no
per-key serialization, durable queue, worker-admission quota or cross-process
fencing here; structural plan bounds do not establish whole-process capacity.

`isReady` checks the context and every generated route plus the closing flag.
It is a health snapshot, not a lock covering a later execution. An unavailable
native consumer fails its reached direct request instead of silently succeeding.
Bootstrap owns health exposure and interpretation.

Close marks the instance closing, rejects new calls, waits for active calls
within the configured grace, then stops Camel and closes its producer. Interrupt
preserves the interrupt flag and proceeds to shutdown; the remaining budget is
passed to Camel. Arbitrary callback code may outlive that grace, so close is not
proof that every caller thread terminated. Owners must release/join their own
workers. Later close calls return once closing is set; they do not supply a
second concurrent shutdown-completion barrier. Readiness stays false and the
instance cannot reopen. Shutdown failures propagate to the owner.

The [tests](../../../../../../../../test/java/com/iocextractor/adapter/processing/camel/README.md) check native UnitOfWork, isolation,
recovery, stopped routes and coordinated shutdown. Cross-module behavior is
described in [processing](../../../../../../../../../../../docs/dev/processing.md); performance claims
require the separately scoped [capacity evidence](../../../../../../../../../../../docs/dev/native-capacity.md).
