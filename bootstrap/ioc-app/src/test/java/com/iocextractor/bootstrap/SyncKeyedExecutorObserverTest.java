package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class SyncKeyedExecutorObserverTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SyncKeyedExecutorObserver.class);

    @AfterEach
    void detachAppenders() {
        logger.detachAndStopAllAppenders();
        logger.setAdditive(true);
    }

    @Test
    void recordsExecutorDegradationAndLogsAtInfoVisibleLevels() {
        var appender = appender();
        SyncHealthState healthState = new SyncHealthState(
                Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC));
        SyncKeyedExecutorObserver observer = new SyncKeyedExecutorObserver(healthState);
        WorkKey key = WorkKey.of("endpoint-a");

        observer.rejected(WorkAdmission.rejected(key, 64));
        observer.failed(key, new IllegalStateException("work failed"));
        observer.dispatchRejected(key, 2, new RejectedExecutionException("dispatch rejected"));

        assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.ERROR, Level.ERROR);
        assertThat(healthState.keyedExecutorSignals().get("endpoint-a"))
                .satisfies(signal -> {
                    assertThat(signal.shedToReconcile()).isTrue();
                    assertThat(signal.rejectedQueuedDepth()).isEqualTo(64);
                    assertThat(signal.abandonedWork()).isEqualTo(2);
                    assertThat(signal.error()).contains("work failed");
                    assertThat(signal.lastDispatchFailure()).contains("dispatch rejected");
                });
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

    private static final class PreparingListAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent eventObject) {
            eventObject.prepareForDeferredProcessing();
            super.append(eventObject);
        }
    }
}
