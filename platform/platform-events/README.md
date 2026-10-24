# platform-events

JDK-only metadata and publication contracts for service-owned local control
events. Requires Java 21. The coordinate is
`io.github.xorex-lc:ioc-platform-events:0.3.0-SNAPSHOT`. Packaging produces a
parent-independent POM, plain JAR, sources and strict Javadoc. Independent
packaged consumers and registry publication remain pending; the `events`
selector currently supports local packaging and manifest checks.

**Layer rule:** no Spring, IOC domain/application types, transport libraries,
serialization, keyed execution or delivery infrastructure. Services define the
payload, listeners and durable recovery. Delivery adapters remain outside this
module.

## Public API

| Type | Responsibility |
|---|---|
| `ControlEvent` | Service-owned payload exposing non-null metadata |
| `ControlEventMetadata` | Immutable producer-supplied identity, type/version, occurrence time, correlation and optional causation |
| `ControlEventPublisher` | Publish one non-null event; the implementation defines delivery and failure behavior |
| `ControlEventObserver` | Observe publication calls and individual dispatch attempts without business work |
| `NoopControlEventPublisher` | Validate the event reference and discard it |
| `NoopControlEventObserver` | Validate callback arguments and discard the observation |
| `RecordingControlEventPublisher` | Unbounded in-memory recording fixture storing the supplied event references |

Metadata rejects null/blank required text, non-positive versions, a null timestamp
and blank non-null causation. Non-blank text is preserved, including surrounding
whitespace. The library does not generate IDs or timestamps, validate a type
registry or promise uniqueness, schema compatibility or tracing-context setup.
`causationId()` may return null; `causation()` returns an `Optional`.

## Delivery and observation

Returning from `publish` does not establish delivery to any or every handler.
A failing call may follow partial delivery. Services decide when to publish and
how to recover; the port has no subscriptions, acknowledgements or retry policy.

`published` reports a normal return from the underlying publication call.
`publishFailed` reports its runtime failure; it does not mean that no listener
acted. Dispatch callbacks describe one handler attempt. Callback exception
containment belongs to their caller, not to this interface.

The no-op implementations are stateless and safe to share. They do not read
metadata. Null events/failures cause `NullPointerException`; a null or blank
handler name causes `IllegalArgumentException`.

## Recording fixture

`RecordingControlEventPublisher` retains duplicates and has no capacity limit or
durable journal. Each `events()` call returns an immutable list snapshot; later
publications cannot grow that list. It holds the original event references and
does not freeze mutable payloads. Create a new instance per test/fixture.

Concurrent publication and snapshots are safe. Sequential calls retain order;
overlapping calls have no invocation-time ordering guarantee. Copy-on-write
storage copies the list on publication, so this fixture is unsuitable for a
long-running production event history.

## Verification and references

`ControlEventMetadataTest` covers required/optional fields, nulls, blank Unicode
text, version boundaries and value preservation. Publisher/observer tests cover
input validation, shallow immutable snapshots, duplicates and concurrent recording
with bounded worker cleanup. These tests qualify the JDK contract, not a Spring
adapter or independently published artifact. Publication packaging rejects any
dependency, private parent, unresolved property, foreign class or executable
Boot layout; the embedded POM must match the standalone POM. See the
[publication guide](../../docs/guides/library-publication.md#events-packaging-preparation)
for the two-artifact release order and remaining consumer gate.

- [Consumer guide](../../docs/guides/events-library.md): a service-defined event and local recording example.
- [Event coordination](../../docs/dev/event-coordination.md): IOC producers, durable backstops and Spring composition.
- `src/main/java/com/iocextractor/platform/events/`: public API and package documentation.
- `src/test/java/com/iocextractor/platform/events/`: JDK contract tests.
