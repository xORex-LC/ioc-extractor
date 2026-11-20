package com.iocextractor.platform.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        ControlEvent first = event("event-1");
        publisher.publish(first);
        List<ControlEvent> snapshot = publisher.events();

        publisher.publish(event("event-2"));

        assertThat(snapshot).containsExactly(first);
        assertThatThrownBy(() -> snapshot.add(event("event-3")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordingPublisherRejectsNullWithoutChangingItsSnapshot() {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();

        assertThatThrownBy(() -> publisher.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event");
        assertThat(publisher.events()).isEmpty();
    }

    @Test
    void recordingPublisherRetainsRepeatedReferencesWithoutReadingMetadata() {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        ControlEvent event = () -> {
            throw new AssertionError("The publisher must not inspect event metadata");
        };

        NoopControlEventPublisher.INSTANCE.publish(event);
        publisher.publish(event);
        publisher.publish(event);

        assertThat(publisher.events()).containsExactly(event, event);
    }

    @Test
    void recordingSnapshotDoesNotFreezeServiceOwnedPayload() {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        List<String> workIds = new ArrayList<>(List.of("work-1"));
        PayloadEvent event = new PayloadEvent(event("event-1").metadata(), workIds);
        publisher.publish(event);
        List<ControlEvent> snapshot = publisher.events();

        workIds.add("work-2");

        assertThat(snapshot.getFirst()).isSameAs(event);
        assertThat(((PayloadEvent) snapshot.getFirst()).workIds()).containsExactly("work-1", "work-2");
    }

    @Test
    @Timeout(20)
    void recordingPublisherKeepsAllEventsFromConcurrentWriters() throws Exception {
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        CyclicBarrier start = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> publishBatch(publisher, start, "first"));
            var second = workers.submit(() -> publishBatch(publisher, start, "second"));
            List<ControlEvent> expected = new ArrayList<>(first.get(5, TimeUnit.SECONDS));
            expected.addAll(second.get(5, TimeUnit.SECONDS));

            assertThat(publisher.events()).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static List<ControlEvent> publishBatch(RecordingControlEventPublisher publisher,
                                                  CyclicBarrier start, String prefix) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        List<ControlEvent> batch = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            ControlEvent event = event(prefix + "-" + index);
            publisher.publish(event);
            batch.add(event);
        }
        return batch;
    }

    private static ControlEvent event(String eventId) {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                eventId, "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }

    private record PayloadEvent(ControlEventMetadata metadata, List<String> workIds) implements ControlEvent {
    }
}
