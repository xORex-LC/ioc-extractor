package com.iocextractor.platform.events;

import java.util.Objects;

/**
 * Validates callback arguments and discards the observation without inspecting event metadata.
 *
 * <p>Null events and failures cause {@link NullPointerException}. A null or blank handler
 * name causes {@link IllegalArgumentException}; names are not trimmed. The singleton has no
 * mutable state and can be shared between threads.</p>
 */
public enum NoopControlEventObserver implements ControlEventObserver {
    /** Shared stateless observer. */
    INSTANCE;

    @Override
    public void published(ControlEvent event) {
        Objects.requireNonNull(event, "event");
    }

    @Override
    public void publishFailed(ControlEvent event, RuntimeException failure) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(failure, "failure");
    }

    @Override
    public void dispatching(ControlEvent event, String handlerName) {
        Objects.requireNonNull(event, "event");
        requireText(handlerName, "handlerName");
    }

    @Override
    public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
        Objects.requireNonNull(event, "event");
        requireText(handlerName, "handlerName");
        Objects.requireNonNull(failure, "failure");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
