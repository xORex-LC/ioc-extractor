package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlEventPublisherTest {

    @Test
    void noopPublisherAcceptsNonNullEvents() {
        assertThatNoException().isThrownBy(() -> NoopControlEventPublisher.INSTANCE.publish(event("event-1")));
    }

    @Test
    void noopPublisherRejectsNullEvents() {
        assertThatThrownBy(() -> NoopControlEventPublisher.INSTANCE.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
    }

    @Test
    void recordingPublisherKeepsPublicationOrder() {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        ControlEvent first = event("event-1");
        ControlEvent second = event("event-2");

        publisher.publish(first);
        publisher.publish(second);

        assertThat(publisher.events()).containsExactly(first, second);
    }

    @Test
    void recordingPublisherReturnsImmutableSnapshot() {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        publisher.publish(event("event-1"));

        assertThatThrownBy(() -> publisher.events().add(event("event-2")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ControlEvent event(String eventId) {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                eventId, "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }
}
