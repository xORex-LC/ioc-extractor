# Use local control-event contracts

[Русская версия](../ru/libraries/events.md) · [Library catalog](README.md)

`ioc-platform-events` supplies a small publish-only port and metadata for local
control events. Use it to announce a fact or nudge work recoverable from the
service's own state. The service defines concrete event classes, payloads,
listeners, delivery adapters and recovery.

This is a JDK-only contract, not a broker or a cross-process message format.
It contains no subscriptions, queue, executor, acknowledgements, transaction,
outbox, serialization, deduplication or retry. Events may accelerate recovery;
service-owned durable state and reconciliation remain authoritative.

## Dependency and qualification

Use Java 21. There are no production dependencies beyond the JDK:

```xml
<dependency>
  <groupId>io.github.xorex-lc</groupId>
  <artifactId>ioc-platform-events</artifactId>
  <version>0.3.0-rc.1</version>
</dependency>
```

Release `0.3.0-rc.1` is available from Central and GitHub Packages. Central needs
no extra repository declaration; GitHub package reads require authenticated
settings. See the [publication procedure](../library-publication.md#consumption).
Separate cold JDK consumers passed for both repositories in
[run 40160627532](https://github.com/xORex-LC/ioc-extractor/actions/runs/40160627532).
The [permanent release record](../../../.github/release-notes/ioc-platform-events-0.3.0-rc.1.md)
retains source, signed hashes and qualification evidence.

The reviewed implementation matches tag `ioc-platform-events-v0.3.0-rc.1`;
the reactor remains at `0.3.0-SNAPSHOT`. Packages retain
`com.iocextractor.platform.events`. Current POMs/publication remain lockstep;
the accepted [development policy](development.md) gives the events family an
independent version after technical activation. The metadata's `eventVersion`
is a separate service-owned payload version.
For Spring delivery use the same-version [explicit adapter](events-spring.md).
A second deployed service and a wire protocol remain outside qualification.

## Public tools

Seven public types live in `com.iocextractor.platform.events`.

| Type | Use |
|---|---|
| `ControlEvent` | Service-owned event exposing `ControlEventMetadata metadata()`; concrete payload validation remains local |
| `ControlEventMetadata` | Immutable six-component record; constructor and `withoutCausation(...)` validate supplied metadata; `causation()` wraps the nullable accessor in `Optional<String>` |
| `ControlEventPublisher` | Functional `void publish(ControlEvent)` port; each implementation defines delivery, ordering, failure handling and thread safety |
| `ControlEventObserver` | Four publication/dispatch callbacks for telemetry; no subscriptions or business handlers |
| `NoopControlEventPublisher` | Stateless `INSTANCE`; rejects a null event, otherwise discards the reference |
| `NoopControlEventObserver` | Stateless `INSTANCE`; validates callback arguments and discards observations |
| `RecordingControlEventPublisher` | Public empty constructor, `publish(ControlEvent)` and `List<ControlEvent> events()` for small tests/fixtures |

## Define an event and exercise a publisher

Save `LocalEventsExample.java` in a consumer's `src/main/java/`, set
`maven.compiler.release=21` and add the dependency above. Run its `main` method.
The complete example uses an unrelated collector event and the recording test
double. Production composition supplies a delivery adapter through the same port.

```java
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.RecordingControlEventPublisher;

import java.time.Instant;
import java.util.Objects;

public final class LocalEventsExample {
    public record RefreshRequested(ControlEventMetadata metadata, String sourceId)
            implements ControlEvent {
        public RefreshRequested {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(sourceId, "sourceId");
        }
    }

    public static void main(String[] args) {
        var recording = new RecordingControlEventPublisher();
        announce(recording);
        System.out.println(recording.events().getFirst().metadata().eventType());
    }

    private static void announce(ControlEventPublisher publisher) {
        var metadata = ControlEventMetadata.withoutCausation(
                "refresh-17", "collector.refresh-requested", 1,
                Instant.parse("2026-10-24T12:00:00Z"), "poll-6");
        publisher.publish(new RefreshRequested(metadata, "feed-a"));
    }
}
```

Expected standard output is `collector.refresh-requested`. One event reference
was recorded; no handler ran. `sourceId` and payload checks belong to the service.
The interface requires non-null metadata, but publishers need not inspect it.
The no-ops and recorder check the event reference only; they cannot certify a
custom implementation's metadata or payload.

Prefer immutable event payloads. A Java record does not make a contained list or
map immutable: copy mutable inputs when constructing the event, or publish a
stable reference to service-owned durable work. The port adds no defensive copy
or synchronization; listeners may receive the same object on different threads
according to the chosen delivery adapter.

## Choose metadata explicitly

| Record component | Meaning and validation |
|---|---|
| `String eventId` | Required nonblank producer-chosen ID; no uniqueness or deduplication check |
| `String eventType` | Required nonblank service vocabulary; no type registry or naming scheme enforced |
| `int eventVersion` | Positive payload version; no schema negotiation or compatibility check |
| `Instant occurredAt` | Required occurrence time; no comparison with the current clock |
| `String correlationId` | Required nonblank link between related work; no automatic MDC/tracing installation |
| `String causationId` | Null for no immediate cause, otherwise nonblank; `causationId()` remains nullable |

Text is retained exactly, including surrounding whitespace. Required text that
is null or `String.isBlank()` causes `IllegalArgumentException`, as do a
nonpositive version and blank non-null causation. A null timestamp causes
`NullPointerException`. `withoutCausation(String, String, int, Instant, String)`
sets the last component to null; `causation()` returns empty or the supplied ID.
Ordinary record equality includes all components. IDs/timestamps are not
created, replaced or propagated by the library.

## Interpret publication and observation

`publish(null)` violates the publisher contract and is rejected with
`NullPointerException` by the supplied implementations. Custom publishers must
honor the port themselves; the interface adds no wrapper or validation engine.
A normal return alone proves neither handler execution nor durable completion.
A failed call may follow partial delivery, so derive retry/idempotency choices
from service state rather than the return value or event ID.

| `ControlEventObserver` callback | Observation |
|---|---|
| `published(ControlEvent)` | Underlying publication call returned normally, even if no handler ran or asynchronous work is unfinished |
| `publishFailed(ControlEvent, RuntimeException)` | Publication call failed; some handlers may already have acted |
| `dispatching(ControlEvent, String)` | One service-owned handler attempt, with a service-defined name |
| `dispatchFailed(ControlEvent, String, RuntimeException)` | One handler failed; it does not undo earlier effects |

Callbacks are telemetry, not business work. Their caller owns exception handling.
The Spring adapter invokes only publication callbacks; a service dispatcher
chooses whether to report individual handler attempts. No-op callbacks reject
null events/failures with `NullPointerException` and null/blank handler names
with `IllegalArgumentException`; nonblank names are not trimmed. No-op publisher
and observer have no mutable state and can be shared between threads. They never
read metadata, deliver work or notify another observer.

## Use the recorder within its limits

Each call retains the original event reference, including duplicates. `events()`
returns an immutable shallow snapshot; later publications do not grow it, but
mutable payloads remain shared. The recorder has no clear/reset operation:
create a new instance for each test instead of treating it as a journal.

Concurrent publication and snapshots are safe. Sequential calls retain order;
overlapping calls have no invocation-time ordering promise. Copy-on-write
storage copies the list on every publication, with no capacity bound or
persistence. This is unsuitable for a long-running production event history.
It starts no worker, opens no resource and requires no close operation.

## Sources, tests and maintenance

- [Module reference](../../../platform/platform-events/README.md),
  [source and package Javadoc](../../../platform/platform-events/src/main/java/com/iocextractor/platform/events/)
  define exact public contracts. Concrete events, handlers and policies stay local.
- [Module tests](../../../platform/platform-events/src/test/java/com/iocextractor/platform/events/)
  cover metadata boundaries, no-ops, duplicate references, immutable snapshots
  and concurrent recording with bounded cleanup. Run
  `make test-module MODULE=platform/platform-events`.
- [Standalone JDK consumer](../../../tools/fixtures/events-consumer/README.md)
  compiles this actual Java block against packaged JARs with an empty cache,
  checks dependency absence, four primary hashes/origins and routing negatives.
- [Publication contracts](../../../tools/tests/library-publication-test.py)
  enforce EN/RU Java/XML parity and exact extraction: `make library-publication-test`.
  Run `make docs` for links. [IOC event coordination](../../dev/event-coordination.md)
  describes durable backstops and service wiring; the
  [Spring decision](../../ADR/0047-explicit-spring-control-event-adapter.md)
  records the two-artifact boundary.

Update both languages, Javadoc and independent consumers when supported behavior
changes. Keep publication/version ownership in the
[publication procedure](../library-publication.md#events-api-and-version-ownership).
An existing coordinate/tag is immutable. Another service deployment, external
broker, new wire format or all-handler completion requires separate evidence.
