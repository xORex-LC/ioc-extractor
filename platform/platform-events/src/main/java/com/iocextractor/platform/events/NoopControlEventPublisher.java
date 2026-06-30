package com.iocextractor.platform.events;

import java.util.Objects;

/** Publisher implementation that validates events and performs no delivery. */
public enum NoopControlEventPublisher implements ControlEventPublisher {
    INSTANCE;

    @Override
    public void publish(ControlEvent event) {
        Objects.requireNonNull(event, "event");
    }
}
