package com.iocextractor.platform.events;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unbounded in-memory publisher for tests and small local fixtures.
 *
 * <p>Each call stores the event reference, including duplicates. Nothing is delivered, persisted
 * or deduplicated, and metadata is not inspected. A mutable payload remains mutable through
 * that reference. Create a new publisher for each fixture rather than treating it as a journal.</p>
 *
 * <p>Concurrent publication and snapshots are safe. Sequential calls retain their order;
 * overlapping calls have no promised order based on invocation time. Copy-on-write storage
 * copies the list on each publication and has no capacity limit.</p>
 */
public final class RecordingControlEventPublisher implements ControlEventPublisher {

    private final List<ControlEvent> events = new CopyOnWriteArrayList<>();

    /** Creates an empty recording publisher. */
    public RecordingControlEventPublisher() {
    }

    @Override
    public void publish(ControlEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /**
     * Returns an immutable list of the event references recorded at one point in time.
     *
     * <p>Later publications do not change this list. Event payloads are not copied or frozen.</p>
     *
     * @return a shallow snapshot, empty before the first accepted publication
     */
    public List<ControlEvent> events() {
        return List.copyOf(events);
    }
}
