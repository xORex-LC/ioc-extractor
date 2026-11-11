# com.iocextractor.adapter.out.events.spring

## Purpose and structure

Publication bridge from framework-free control events to a caller-owned Spring
publisher. Follow the [module dependency rules](../../../../../../../../../README.md).

| File | Responsibility |
|---|---|
| [SpringControlEventPublisher.java](SpringControlEventPublisher.java) | Single public implementation: explicit construction, one delegate attempt, publication observation and final fallback WARN |
| [package-info.java](package-info.java) | Service ownership and absence of auto-configuration |

The public constructor requires a publisher and observer. The package-private
logger-injection constructor exists for local contract testing; it is not a
separate public extension point. Precise API guarantees remain in Javadoc.

## Call and failure boundaries

`publish` rejects a null event before delegate or observer invocation. Otherwise
it forwards the original reference once without inspecting metadata or copying
the payload. It does not invoke per-handler `dispatching`/`dispatchFailed`
callbacks; dispatch observation belongs to service listeners.

| Delegate outcome | Publication observation |
|---|---|
| Normal return | Call `observer.published(event)` |
| Ordinary `RuntimeException` | Call `observer.publishFailed(event, originalFailure)` and normally return |
| Direct `Error` | Propagate it without a publication callback |
| `UndeclaredThrowableException` with an immediate undeclared `Error` | Rethrow that original Error without a publication callback |
| Other runtime wrapper, including a deeper Error cause | Preserve it as the runtime failure; do not search arbitrary cause chains |

A normal return may mean no matching listeners, asynchronous hand-off or a
failure already consumed by a service error handler. A synchronous failure may
follow completed effects in earlier listeners. Neither case authorizes blind
retry or establishes durable completion. Listener timing/order, transaction
boundaries and continuation policy come from the service's publisher settings.

## Observer and logging failures

Observer callbacks run synchronously on the publishing caller's thread.

- Runtime failure from `published` produces one best-effort WARN. It does not
  reclassify delivery as failed, invoke `publishFailed` or retry publication.
- Runtime failure from `publishFailed` retains the original publication exception
  as the WARN's primary throwable. A distinct observer failure is added as
  suppressed when supported; self-suppression is avoided. This mutates the
  original publication throwable.
- A runtime exception from the final logger is contained, with no recursive
  logging attempt. Errors from observation or fallback logging propagate.

The fallback WARN exists for observer failure, not every delegate failure.
With `NoopControlEventObserver`, an ordinary runtime publication failure normally
returns without an adapter WARN or a caller-visible failure signal. A logging
provider alone does not make those failures observable; choose an appropriate
service observer.

Exception text, causes and suppressed evidence can reach the backend. The service
owns their disclosure policy; this adapter does not mask arbitrary event or
exception data and cannot guarantee that a broken observer/backend records it.

## Ownership and concurrency

Dependencies are final and publication state is local to the call. Concurrent
callers still require a thread-safe delegate and observer; final references do
not make their mutable state safe. The adapter owns no executor, listener
registration, cancellation, timeout or close lifecycle.

Heavy work, durable idempotency and retries belong to consumers and their
application owners. Async worker failures after submission are outside this
call's observation boundary. Metadata correlation does not automatically install
MDC, trace propagation or an event wire protocol.

For IOC composition, see [EventCoordinationConfig](../../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/EventCoordinationConfig.java)
and the service-owned [LoggingControlEventObserver](../../../../../../../../../../../bootstrap/ioc-app/src/main/java/com/iocextractor/bootstrap/LoggingControlEventObserver.java).
Core/domain code depends on framework-free event contracts, not this Spring type.

## Verification and related docs

The [test package](../../../../../../../../test/java/com/iocextractor/adapter/out/events/spring/README.md) maps exact failure and real-context cases.
The [consumer guide](../../../../../../../../../../../docs/guides/libraries/events-spring.md) provides executable
usage, while [event coordination](../../../../../../../../../../../docs/dev/event-coordination.md) owns IOC's
ledger/reconcile backstops. A publisher outcome is not a handler completion receipt.
