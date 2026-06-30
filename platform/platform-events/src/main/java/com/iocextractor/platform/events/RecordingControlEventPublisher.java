package com.iocextractor.platform.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory publisher test double that records accepted events in publication order. */
public final class RecordingControlEventPublisher implements ControlEventPublisher {

    private final List<ControlEvent> events = new ArrayList<>();

    @Override
    public void publish(ControlEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** Returns an immutable snapshot of recorded events. */
    public List<ControlEvent> events() {
        return List.copyOf(events);
    }
}
