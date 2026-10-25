package com.iocextractor.platform.events;

import java.util.Objects;

/**
 * Discards each non-null event without reading its metadata or invoking an observer.
 *
 * <p>The singleton has no mutable state and can be shared between threads.</p>
 */
public enum NoopControlEventPublisher implements ControlEventPublisher {
    /** Shared stateless publisher. */
    INSTANCE;

    @Override
    public void publish(ControlEvent event) {
        Objects.requireNonNull(event, "event");
    }
}
