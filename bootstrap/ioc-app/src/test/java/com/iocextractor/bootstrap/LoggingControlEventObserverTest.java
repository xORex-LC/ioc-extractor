package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.platform.events.ControlEvent;
import com.iocextractor.platform.events.ControlEventMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingControlEventObserverTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingControlEventObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void logsSuccessfulSignalsAtDebugAndFailuresAtError() {
        var appender = appender();
        var observer = new LoggingControlEventObserver();
        ControlEvent event = event();
        RuntimeException failure = new IllegalStateException("dispatch failed");

        observer.published(event);
        observer.dispatching(event, "handler");
        observer.publishFailed(event, failure);
        observer.dispatchFailed(event, "handler", failure);

        assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG, Level.DEBUG, Level.ERROR, Level.ERROR);
    }

    private ControlEvent event() {
        return new TestControlEvent(ControlEventMetadata.withoutCausation(
                "event-1", "test.event", 1, Instant.parse("2026-06-30T12:00:00Z"), "corr-1"));
    }

    private ListAppender<ILoggingEvent> appender() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        var appender = new PreparingListAppender();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private record TestControlEvent(ControlEventMetadata metadata) implements ControlEvent {
    }

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
