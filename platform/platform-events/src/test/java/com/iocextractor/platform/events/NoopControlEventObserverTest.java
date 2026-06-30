package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;

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

    @Test
    void rejectsBlankHandlerName() {
        assertThatThrownBy(() -> NoopControlEventObserver.INSTANCE.dispatching(event(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handlerName");
    }

    private static ControlEvent event() {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                "event-1", "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }
}
