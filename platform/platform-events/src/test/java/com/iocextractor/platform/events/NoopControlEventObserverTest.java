package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoopControlEventObserverTest {

    @Test
    void acceptsValidPublicationAndDispatchSignals() {
        ControlEvent event = event();
        RuntimeException failure = new RuntimeException("boom");

        assertThatNoException().isThrownBy(() -> {
            NoopControlEventObserver.INSTANCE.published(event);
            NoopControlEventObserver.INSTANCE.publishFailed(event, failure);
            NoopControlEventObserver.INSTANCE.dispatching(event, "handler");
            NoopControlEventObserver.INSTANCE.dispatchFailed(event, "handler", failure);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsMissingHandlerName(String handlerName) {
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatching(event(), handlerName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handlerName");
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatchFailed(
                event(), handlerName, new RuntimeException("handler failed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handlerName");
    }

    @Test
    void rejectsNullEventInEveryCallback() {
        RuntimeException failure = new RuntimeException("failed");

        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.published(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.publishFailed(null, failure))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatching(null, "handler"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatchFailed(null, "handler", failure))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("event");
    }

    @Test
    void rejectsNullFailureInFailureCallbacks() {
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.publishFailed(event(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("failure");
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatchFailed(event(), "handler", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("failure");
    }

    @Test
    void acceptsNonBlankHandlerNamesWithoutReadingMetadata() {
        ControlEvent event = () -> {
            throw new AssertionError("The observer must not inspect event metadata");
        };

        assertThatNoException().isThrownBy(() -> {
            NoopControlEventObserver.INSTANCE.published(event);
            NoopControlEventObserver.INSTANCE.publishFailed(event, new RuntimeException("failed"));
            NoopControlEventObserver.INSTANCE.dispatching(event, " handler ");
            NoopControlEventObserver.INSTANCE.dispatchFailed(event, " handler ", new RuntimeException("failed"));
        });
    }

    private static ControlEvent event() {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                "event-1", "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }
}
