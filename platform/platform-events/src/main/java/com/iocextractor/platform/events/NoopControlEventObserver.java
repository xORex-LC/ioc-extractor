package com.iocextractor.platform.events;

import java.util.Objects;

/** Observer implementation that validates inputs and performs no side effects. */
public enum NoopControlEventObserver implements ControlEventObserver {
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
