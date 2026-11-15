# Typed events and restoring MDC scopes

This package supplies the logging mechanism without a service catalog or backend.
It uses the JDK and SLF4J API; see the
[module reference](../../../../../../../README.md) and
[package-info.java](package-info.java) for dependency and ownership rules.

## Source map

| Source | Responsibility |
|---|---|
| [LogFieldSpec.java](LogFieldSpec.java) | Immutable dotted key and scalar type declaration |
| [LogValueType.java](LogValueType.java) | STRING, LONG and BOOLEAN wire representations |
| [LogValueNormalizer.java](LogValueNormalizer.java) | Package-private validation and snapshot conversion of supported inputs |
| [LogEvents.java](LogEvents.java) | Fresh TRACE/DEBUG/INFO/WARN/ERROR event factories |
| [LogEvent.java](LogEvent.java) | Mutable event-local values, ECS conveniences and one provider-call attempt per `log` |
| [MdcScope.java](MdcScope.java) | Same-thread restoration of touched MDC keys |
| [ecs/](ecs/README.md) | Shared descriptors and outcome vocabulary |
| [redaction/](redaction/README.md) | Explicit transformations for selected STRING representations |

## Field declarations and scalar snapshots

Keys are case-sensitive dotted paths; each segment follows
`[A-Za-z_][A-Za-z0-9_-]*`. Invalid spelling is rejected, not trimmed or normalized.
There is no process-global registry or automatic namespace/reserved-path policy.
The service owns catalog uniqueness and parent/child path conflicts. Descriptor
record equality includes key and type; an event identifies fields by key alone.

| Type | Accepted inputs | Stored event value |
|---|---|---|
| STRING | CharSequence, Enum, Path, UUID | `toString()` snapshot at field assignment |
| LONG | Byte, Short, Integer, Long | Long, without parsing or unit conversion |
| BOOLEAN | Boolean | Boolean |

Null omits the emitted local value but retains its declaration. Equivalent
same-type descriptors update one value; a different type for that key fails even
after null omission. Rejected updates leave earlier values/declarations intact
and do not establish a new declaration. Arrays, collections, maps, floating-point
numbers, arbitrary objects and numeric/boolean strings are unsupported.
Enum conversion uses `toString()`, not necessarily its Java name or wire value.
Path conversion does not resolve an absolute path.

Field/helper validation precedes level filtering. Null required arguments fail
with `NullPointerException`; invalid keys, scalar representations or conflicting
types fail with `IllegalArgumentException`. Type-mismatch messages omit the input
value, but keys can appear in errors and custom `toString()`/provider failures can
throw their own exceptions. This is not a safe error renderer or secret detector.

## Emission and collisions

A builder belongs to one thread and one record's construction. Its default
message is empty; `message(null)` is invalid and `log(null)` means no cause.
Each `log` is another emission attempt; the builder adds no deduplication,
persistence, retry or fallback logger. With a cause, the original Throwable is
passed unchanged. Messages, values, MDC and exception chains are not sanitized.

At a disabled level, `log` skips collision preparation and provider delivery;
field validation/conversion already happened while constructing the event.
At an enabled level, non-null event-local fields temporarily hide exact same-key
MDC strings, including fields declared LONG/BOOLEAN. Event-local values are then
sent through SLF4J key/value pairs, with restoration attempted while unwinding.
The implementation follows builder instances returned by SLF4J fluent methods.
Provider/building failures propagate.

Null omission does not hide ambient context. Use `MdcScope.hide` when omission
must also remove an ambient value. Exact-key hiding does not remove child paths,
hide an entire object or protect encoder-owned metadata. The
[scalar ECS reference](ecs/README.md) explains that separate ownership boundary.
Appender acceptance can still precede encoding failure or discarded output;
verify bytes/status for the selected provider rather than treating `log` return
as durable completion evidence.

## MDC scope lifecycle

`open()` creates an empty scope without reading or changing MDC. Acquire it as
the resource first, then populate it inside the body so a failed population still
attempts cleanup. `put` accepts only a STRING descriptor and a string, with null
meaning temporary removal. `hide` accepts any declared type.

Open, populate and close on one thread; nested scopes must close in reverse order.
These are caller obligations, not a thread-ownership/LIFO validator. The first
previous value of each touched key is remembered, even after repeated changes.
Close restores touched keys in reverse first-touch order, preserving unrelated
MDC writes. Successful close is idempotent; subsequent writes fail with
`IllegalStateException`.

Scope/provider failures propagate. Cleanup can be partial if the MDC provider
fails; the scope marks itself closed only after restoration completes. A secondary
close failure follows normal try-with-resources suppression. No complete cleanup
or context delivery can be promised through a failing or MDC-less provider.
The scope is mutable and not thread-safe, and it propagates nothing to executors
or network calls. Workers reconstruct their own context from explicit metadata.

Read the [consumer guide](../../../../../../../../../docs/guides/libraries/logging-slf4j.md) for complete
examples and the [test reference](../../../../../../test/java/com/iocextractor/platform/logging/README.md)
for provider faults, cleanup and async evidence limits.
