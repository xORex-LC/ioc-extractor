# Synchronous diagnostic delivery

This package supplies the diagnostic output port and small in-process sinks.
It uses only the JDK and sibling diagnostic values. Logging, rendering, redaction
and service recovery belong to consumer adapters; see the
[module reference](../../../../../../../../README.md) and
[package-info.java](package-info.java).

## Source map

| Source | Responsibility |
|---|---|
| [DiagnosticSink.java](DiagnosticSink.java) | Driven `emit(Diagnostic)` port; synchronous, observational delivery |
| [NoopDiagnosticSink.java](NoopDiagnosticSink.java) | Shared stateless `INSTANCE` that discards non-null input |
| [CollectingDiagnosticSink.java](CollectingDiagnosticSink.java) | Unbounded ordered accumulation with immutable list snapshots; intended for tests and report assembly |
| [ResilientDiagnosticSink.java](ResilientDiagnosticSink.java) | Delegate plus application-supplied runtime-failure callback |

## Delivery and ownership

Producers own ordering and must not deduplicate occurrences merely because they
compare equal. Delivery attached to processing should not change its control
flow: wrap potentially failing delegates in `ResilientDiagnosticSink`.

The wrapper invokes the delegate once on the caller's thread. If it throws a
`RuntimeException`, the fallback receives the exact diagnostic and failure once.
Runtime failures from the fallback are contained, without recursion; `Error`
from either callback propagates. Null input is rejected before callbacks. A
failed delegate may already have produced a partial external effect; containment
does not make delivery atomic or successful.

No sink here adds workers, queues, persistence, retries, timeouts or resource
cleanup. The caller owns callback latency/resources and downstream disclosure.
The port promises no thread safety: the collecting sink is mutable and not
thread-safe; the resilient wrapper's safety depends on its callbacks. Collecting
snapshots copy the list, not the diagnostic's referenced objects.

See the [sink tests](../../../../../../../test/java/com/iocextractor/platform/diagnostics/README.md)
for ordering, null rejection and failure behavior, and the
[consumer guide](../../../../../../../../../../docs/guides/libraries/diagnostics.md#isolate-failures-when-reporting-diagnostics)
for an executable delivery example. IOC's concrete logging implementation lives
in [platform-diagnostics-logging](../../../../../../../../../platform-diagnostics-logging/README.md).
