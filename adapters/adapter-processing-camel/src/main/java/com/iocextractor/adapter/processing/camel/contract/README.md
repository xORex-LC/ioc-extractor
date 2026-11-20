# com.iocextractor.adapter.processing.camel.contract

## Purpose and structure

JDK-only technical vocabulary for a bounded plan and one invocation's results.
Follow the [module boundary](../../../../../../../../../README.md). This package carries no
IOC model, Spring or Camel type. Registration/admission remains separate from
record construction.

| File | Responsibility |
|---|---|
| [PlanDescriptor.java](PlanDescriptor.java) | Named views/recovery edges, ordered branches, selection mode and explicit unmatched action |
| [Condition.java](Condition.java) | Ordered leaf/ALL/ANY/NOT syntax; leaf arguments are copied string maps |
| [ViewOutcome.java](ViewOutcome.java) | Available value, optional absence or expected unavailability |
| [BranchOutcome.java](BranchOutcome.java) | Prepared candidate, intentional filtered result or expected failure with optional opaque evidence |
| [FailureReference.java](FailureReference.java) | Producer/reason identity without diagnostic severity or durable authority |
| [PlanSelection.java](PlanSelection.java) | Selected, blocked and ambiguous branch evidence |
| [PlanExecutionResult.java](PlanExecutionResult.java) | Ordered replies, preparation-blocked branches, demanded failure consumers and recovery attempts |
| [RoutingExecutionScopes.java](RoutingExecutionScopes.java) | Caller hooks opening AutoCloseable view/branch scopes |
| [NoopRoutingExecutionScopes.java](NoopRoutingExecutionScopes.java) | Stateless default scopes |
| [RoutingTraceEvent.java](RoutingTraceEvent.java) | Decision metadata and bounded rule/reason-code sanitization |
| [RoutingTraceSink.java](RoutingTraceSink.java) | Optional trace enablement/callback and default emission containment |
| [NoopRoutingTraceSink.java](NoopRoutingTraceSink.java) | Disabled default observer |

## Descriptor and outcome meaning

`original` is the reserved root view. Ordinary views refer to one registered
operation and input; a recovery view uses `view.recover` with an explicit
alternate/reason allowlist. Branch eligibility may be null (unconditional);
`requiredViews` are demanded only for selected branches. `onUnmatched` explicitly
chooses SKIP, REJECT or ROUTE to a separately declared default branch. Constructors
copy collection structure and check basic nulls; the
[compiler](../compile/README.md) validates IDs, graph/reference consistency
and limits before runtime construction.

Unavailable is not ordinary false, and NOT does not turn failure into a match.
Absent is separate from expected failure: only an absence-aware registered
predicate may inspect it; a required value instead becomes `ABSENT_REQUIRED`.
`BranchOutcome.Unavailable` does not cause route reselection or fallback.
Prepared candidates still need caller-owned validation, identity, checkpoint
and durable commit. Opaque failure evidence is returned locally, not traced as
payload or interpreted as an IOC diagnostic here.

`PlanSelection` describes eligibility; `preparationBlocked` separately records
selected branches whose mapping-only views failed. `FailureResolution` reports
only actually reached consumers as recovered/unrecovered. `RecoveryAttempt`
retains the primary failure and an alternate failure when recovery fails.
The same producer failure can be recovered for one consumer and unrecovered for
another. Unreached conditions/views create no invented failure evidence.

## Ownership and observation

Lists/maps/sets are defensive read-only snapshots. Objects inside
`Available`, `Prepared`, `BranchInput.original`, resolved views and opaque evidence
are borrowed references, not deeply frozen/copied values. The caller must keep
them immutable or safely shared for their lifetime, including concurrent calls.
Returned replies are ordered immutable collections, not a commit receipt.

Default `RoutingTraceSink.emit` builds an event only when enabled and contains
RuntimeExceptions from enablement/event construction/callback. Errors propagate;
an override must preserve the intended observational boundary. Trace events have
no payload field. Malformed rule/reason IDs become `INVALID_RULE_ID` /
`INVALID_REASON_CODE` in trace evidence only; returned failures retain their
original references. Other metadata fields are not arbitrary-text redaction.

Scopes wrap actual operation/destination callbacks and are closed on exit,
including exceptional exit. Scope open/close failures are execution failures,
not trace failures. The module does not implement MDC itself; bootstrap supplies
that policy. See the [runtime](../runtime/README.md) and
[tests](../../../../../../../../test/java/com/iocextractor/adapter/processing/camel/README.md) for execution and evidence boundaries.
