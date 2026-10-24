# Local control-event contracts

Use a control event to announce a fact or nudge work that a service can recover
from its own durable state. A service defines the event payload and chooses the
publisher. The shared contracts require Java 21 and have no runtime dependency
beyond the JDK.

The module is currently reactor-local:
`com.iocextractor:ioc-platform-events:0.3.0-SNAPSHOT`. Publication under
`io.github.xorex-lc:ioc-platform-events` and a separate `ioc-events-spring` adapter
are planned; neither artifact is claimed published or independently consumed.
The [module reference](../../platform/platform-events/README.md) describes all
seven public types and their validation rules.

## Define an event and exercise a publisher

This complete example uses an unrelated collector event and the recording test
double. A production service supplies its delivery adapter through the same
`ControlEventPublisher` port.

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

Running the example prints `collector.refresh-requested`. It records one event
reference and invokes no handler. The source ID and any additional payload
constraints belong to the service.

## Choose IDs and interpret outcomes

Supply event identity, type/version, occurrence time and correlation yourself.
Required text must be non-null and non-blank; surrounding whitespace is preserved.
A version must be positive, but the library does not compare schemas or enforce
type names. Causation may be null; `causation()` exposes it as an `Optional`.
An event ID is not a shared deduplication or recovery mechanism.

A normal `publish` return follows the chosen implementation's contract. It does
not prove durable completion or delivery to every handler. A failure can follow
partial delivery, so decide retry/idempotency behavior from service-owned state.
Observer callbacks report publication and individual dispatch attempts; they do
not install subscriptions or isolate listeners.

## Use the recording fixture within its limits

The recorder keeps every event reference, including duplicates. `events()`
returns an immutable list snapshot that later publications do not change.
Mutable payloads remain shared and can change through their original references.
Concurrent publication/snapshot access is safe; overlapping publishers have no
promised invocation-time order. The list is unbounded and copied on each write.
Use a fresh recorder for each test rather than retaining a production history.

The example is locally compiled and executed against the current module.
Standalone POMs, cold repository consumers and the extracted Spring adapter still
need qualification. IOC's actual wiring and recovery are described in
[event coordination](../dev/event-coordination.md).
