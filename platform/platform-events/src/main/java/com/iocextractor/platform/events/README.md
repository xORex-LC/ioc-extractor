# Local control-event contracts

This JDK-only package supplies service-owned event metadata, a publication port,
telemetry callbacks and small no-op/recording helpers. Delivery mechanisms,
subscriptions, workers and recovery belong outside it. Read the
[module reference](../../../../../../../README.md) for dependency, release
family and verification rules; [package-info.java](package-info.java) states the
package boundary.

## Source map

| Source | Responsibility |
|---|---|
| [ControlEvent.java](ControlEvent.java) | Service-owned payload exposing producer-supplied metadata |
| [ControlEventMetadata.java](ControlEventMetadata.java) | Immutable identity/type/version/time/correlation/optional-causation values |
| [ControlEventPublisher.java](ControlEventPublisher.java) | Functional publish-only port with implementation-defined delivery behavior |
| [ControlEventObserver.java](ControlEventObserver.java) | Publication and individual handler-attempt telemetry; no subscriber registration |
| [NoopControlEventPublisher.java](NoopControlEventPublisher.java) | Stateless validating discard, without delivery or observation |
| [NoopControlEventObserver.java](NoopControlEventObserver.java) | Stateless validation and discard of telemetry calls |
| [RecordingControlEventPublisher.java](RecordingControlEventPublisher.java) | Unbounded thread-safe recording fixture with shallow snapshots |

## Metadata and payload ownership

The producer chooses all IDs, event type, positive payload schema version and
occurrence time. Text must be nonblank and is preserved exactly, including
surrounding whitespace. Causation may be null; `withoutCausation(...)` sets it
to null, and `causation()` exposes it as an Optional. Record equality includes
all six components. No uniqueness, type registry, clock comparison or version
compatibility is enforced. A correlation ID does not install MDC/tracing context.

Null/blank required text, a nonpositive version and blank non-null causation
cause `IllegalArgumentException`; null `occurredAt` causes `NullPointerException`.
The metadata record validates itself, while a concrete `ControlEvent` owns its
payload and non-null metadata contract. The no-ops/recorder only validate the
event reference; they do not call `metadata()` or certify its implementation.

Prefer immutable payloads or stable bounded references to durable work. The
interface copies nothing. A record containing a mutable list/map does not freeze
that payload; callers own its sharing and lifecycle.

## Publication and observation

`publish(null)` violates the port contract. The implementation defines timing,
ordering, exception behavior and thread safety; the interface wraps nothing.
Normal return does not prove handler execution, all-handler completion or durable
delivery. Failure does not prove zero effects or safe retry. Event identity is
not an idempotency ledger. Concrete delivery and recovery decisions belong to
the adapter/service.

| Observer callback | Meaning |
|---|---|
| `published` | Underlying publication call returned normally; asynchronous work may be unfinished |
| `publishFailed` | That publication call failed with a runtime exception; partial delivery may have occurred |
| `dispatching` | One service-owned handler invocation is being attempted |
| `dispatchFailed` | One handler failed with a runtime exception; other handlers may already have acted |

Callbacks are telemetry, not business handlers. Core connects them to no publisher
or dispatcher and provides no exception isolation; their caller owns handling.
In IOC, the separate Spring adapter reports publication callbacks, while service
listeners choose dispatch observations. See the
[adapter reference](../../../../../../../../../adapters/adapter-events-spring/README.md) and
[coordination capability](../../../../../../../../../docs/dev/event-coordination.md) rather than inferring
Spring behavior from these contracts.

## No-ops and recorder

Both no-op singletons have no mutable state and may be shared between threads.
The publisher rejects a null event with `NullPointerException`. The observer
rejects null events/failures with `NullPointerException`, and null/blank handler
names with `IllegalArgumentException`. Nonblank names are not trimmed. No-op
callbacks never inspect metadata or notify another observer.

The recorder stores every original reference, including repeated references
and equal events. Sequential calls retain order; overlapping calls have no
invocation-time ordering guarantee. Concurrent publication and snapshots are safe
through copy-on-write storage. That safety does not make event payloads immutable
or synchronize their later mutation.

`events()` returns an immutable shallow snapshot: later publications do not grow
it, but payload objects remain shared. Each publication copies the underlying
list, and retention has no bound. No reset/clear operation exists; create a fresh
recorder per fixture. Nothing is dispatched, persisted, deduplicated or observed.
These helpers own no workers or closeable resources and are unsuitable as a
long-running production journal.

The [consumer guide](../../../../../../../../../docs/guides/libraries/events.md) owns the executable example
and integration recipe; the
[test reference](../../../../../../test/java/com/iocextractor/platform/events/README.md)
maps current boundary, ownership and concurrency witnesses.
