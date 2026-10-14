# ADR 0040: Native sequential preparation dispatch

- Status: Accepted
- Date: 2026-10-10
- Scope: Camel preparation execution; refines the dispatch mechanism of
  [ADR 0031](0031-bounded-camel-preparation-runtime.md).

## Context

The admitted selector already computes the complete eligible subset, and the
runtime resolves every selected branch's required views before calling any
destination. Routing is local and sequential. Multi-recipient execution added
one dispatch exchange, Recipient List bookkeeping, reply aggregation and wrapper
snapshots around this already selected work. Documents and managed imports now
read bounded private cursors, so retaining whole-document lists is not necessary
to change dispatch granularity.

A direct call to registered processors is not equivalent to native execution:
existing conformance tests require view UnitOfWork completion/failure callbacks
and refusal of stopped routes. Reimplementing these owners in a second evaluator
would weaken the architecture or duplicate Camel lifecycle behavior.

## Decision

Keep one embedded Camel context, bound endpoints, native consumers and the
reused ProducerTemplate. After complete selection and required-view resolution,
send each eligible branch in declared order through that template. Each call
gets a fresh native exchange and UnitOfWork with the same immutable BranchInput.
Append typed replies in an invocation-local list; freeze it at the existing
PlanExecutionResult boundary. Stop on an unexpected exception. Expected
filtered/unavailable replies retain their declared meaning and do not stop
otherwise eligible branches.

Remove the generated dispatch endpoint, its compiled metadata, recipient
property, DispatchRequest and reply aggregation strategy. The only execution
implementation is the native path; there is no operator toggle, fallback engine,
parallel fan-out or shared batch UnitOfWork. Registered operation/destination
processors are never invoked directly by the application or a custom evaluator.

Demand, short-circuit, FIRST/ALL/EXCLUSIVE, explicit view recovery, consumer
failure accounting, trace and MDC remain with their current owners. Every
occurrence still contributes validation, configured preparation and provenance
through its real cursor. Workspace reduction, ordering, identity, admission,
canonical atomicity, receipt recovery and projection are unchanged.

## Evidence and limits

The native prototype passed 55 existing execution conformance tests and paired
Spring document/import output checks. Three alternating 100k-occurrence
whole-service pairs passed independent checks of all five canonical artifacts,
provenance, mutable projections and three immutable export profiles. Median
conservative local time fell from 62.76 to 60.04 seconds; median process CPU
fell from 54.46 to 52.20 seconds. All pair time ratios improved, but individual
improvements ranged from 0.9% to 12.3%; this is a scoped modest benefit, not a
general throughput promise. Repeated-document caller allocations fell about 9%;
the distinct-domain profile improved much less.

These screens still fail the adopted local-time and five-second writer-occupancy
limits. They do not qualify the million-occurrence workload, sustained mixed
load, SMB or a complete G6 acceptance. No target is relaxed. An incomplete
unique import series remains failure evidence after a UTC clock regression;
its coordination follow-up is ING-16 in [known issues](../KNOWN-ISSUES.md).

The production change removes unused compiler/dispatch structures beyond the
timing prototype. Final functional gates and an actual packaged-service screen
qualify that cleanup; prototype timing ratios must not be relabelled as final
packaged implementation ratios.

## Alternatives

- Direct compiled processor execution: rejected by native lifecycle contracts.
- Shared-exchange batch processing: cannot silently share completion, headers,
  properties or failure state between observations. A streaming Split EIP that
  preserves per-observation and per-destination isolation still needs those
  execution boundaries and adds another wrapper. No new cursor/batch API or
  speculative runtime is admitted without its own demonstrated complete-service
  benefit.
- Keep Recipient List: correct but redundant for this already selected local
  sequential topology. Native endpoint calls preserve the framework's execution
  ownership with fewer adapter mechanisms.
