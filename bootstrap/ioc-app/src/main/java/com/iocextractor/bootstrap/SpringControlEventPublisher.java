package com.iocextractor.bootstrap;

import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventObserver;
import com.iocextractor.platform.events.ControlEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;

/** Spring adapter for the framework-free control-event publisher port. */
public final class SpringControlEventPublisher implements ControlEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringControlEventPublisher.class);

    private final ApplicationEventPublisher publisher;
    private final ControlEventObserver observer;

    public SpringControlEventPublisher(ApplicationEventPublisher publisher, ControlEventObserver observer) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void publish(ControlEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            publisher.publishEvent(event);
            observePublished(event);
        } catch (RuntimeException failure) {
            observePublishFailed(event, failure);
            throw failure;
        }
    }

    private void observePublished(ControlEvent event) {
        try {
            observer.published(event);
        } catch (RuntimeException failure) {
            log.warn("control event observer failed after publication", failure);
        }
    }

    private void observePublishFailed(ControlEvent event, RuntimeException publishFailure) {
        try {
            observer.publishFailed(event, publishFailure);
        } catch (RuntimeException observerFailure) {
            publishFailure.addSuppressed(observerFailure);
        }
    }
}
