package com.iocextractor.bootstrap;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SpringControlEventPublisherTest {

    @Test
    void publishesControlEventThroughSpringPublisherAndObserver() {
        List<Object> springEvents = new ArrayList<>();
        RecordingObserver observer = new RecordingObserver();
        SpringControlEventPublisher publisher = new SpringControlEventPublisher(springEvents::add, observer);
        ControlEvent event = event("event-1");

        publisher.publish(event);

        assertThat(springEvents).containsExactly(event);
        assertThat(observer.published).containsExactly(event);
        assertThat(observer.failures).isEmpty();
    }

    @Test
    void doesNotLetObserverFailureBreakSuccessfulPublication() {
        List<Object> springEvents = new ArrayList<>();
        SpringControlEventPublisher publisher = new SpringControlEventPublisher(
                springEvents::add,
                new ThrowingPublishedObserver());
        ControlEvent event = event("event-1");

        assertThatNoException().isThrownBy(() -> publisher.publish(event));
        assertThat(springEvents).containsExactly(event);
    }

    @Test
    void observesAndSwallowsSpringPublicationFailure() {
        RuntimeException failure = new IllegalStateException("publish failed");
        ApplicationEventPublisher failingPublisher = ignored -> {
            throw failure;
        };
        RecordingObserver observer = new RecordingObserver();
        SpringControlEventPublisher publisher = new SpringControlEventPublisher(failingPublisher, observer);
        ControlEvent event = event("event-1");

        assertThatNoException().isThrownBy(() -> publisher.publish(event));
        assertThat(observer.failures).containsExactly(failure);
        assertThat(observer.published).isEmpty();
    }

    private static ControlEvent event(String eventId) {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                eventId, "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }

    private static final class RecordingObserver implements ControlEventObserver {
        private final List<ControlEvent> published = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        @Override
        public void published(ControlEvent event) {
            published.add(event);
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
            failures.add(failure);
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
        }
    }

    private static final class ThrowingPublishedObserver implements ControlEventObserver {
        @Override
        public void published(ControlEvent event) {
            throw new IllegalStateException("observer failed");
        }

        @Override
        public void publishFailed(ControlEvent event, RuntimeException failure) {
        }

        @Override
        public void dispatching(ControlEvent event, String handlerName) {
        }

        @Override
        public void dispatchFailed(ControlEvent event, String handlerName, RuntimeException failure) {
        }
    }
}
