package com.iocextractor.bootstrap;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventCoordinationConfigTest {

    @Test
    void createsSpringBackedControlEventPublisher() {
        EventCoordinationConfig config = new EventCoordinationConfig();
        List<Object> springEvents = new ArrayList<>();
        ControlEventObserver observer = config.controlEventObserver();
        ControlEventPublisher publisher = config.controlEventPublisher(springEvents::add, observer);
        ControlEvent event = new TestControlEvent(ControlEventMetadata.withoutCausation(
                "event-1", "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));

        publisher.publish(event);

        assertThat(publisher).isInstanceOf(SpringControlEventPublisher.class);
        assertThat(observer).isInstanceOf(LoggingControlEventObserver.class);
        assertThat(springEvents).containsExactly(event);
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }
}
