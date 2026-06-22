package com.iocextractor.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.iocextractor.application.port.in.aggregation.AggregatePartitionsUseCase;
import com.iocextractor.application.port.in.aggregation.AggregationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonAggregationSchedulerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DaemonAggregationScheduler.class);
    private final Level originalLevel = logger.getLevel();

    @AfterEach
    void restoreLogger() {
        logger.setLevel(originalLevel);
    }

    @Test
    void idle_aggregation_tick_does_not_log_info() {
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = appender();
        try {
            schedulerReturning(AggregationResult.empty()).runOnce();

            assertThat(appender.list)
                    .noneMatch(event -> event.getLevel() == Level.INFO)
                    .anyMatch(event -> event.getLevel() == Level.DEBUG
                            && event.getFormattedMessage().contains("no ready partitions"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void non_empty_aggregation_logs_info_summary() {
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = appender();
        try {
            AggregationResult result = new AggregationResult(
                    1,
                    2,
                    Map.of("masks", 3),
                    Map.of("masks", 7),
                    3,
                    0,
                    0);

            schedulerReturning(result).runOnce();

            assertThat(appender.list)
                    .anyMatch(event -> event.getLevel() == Level.INFO
                            && event.getFormattedMessage().equals("aggregation completed")
                            && "7".equals(event.getMDCPropertyMap().get("ioc.rows")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private DaemonAggregationScheduler schedulerReturning(AggregationResult result) {
        AggregatePartitionsUseCase useCase = command -> result;
        return new DaemonAggregationScheduler(
                useCase,
                new AggregationState(Clock.systemUTC()),
                Duration.ofMinutes(1),
                Duration.ZERO);
    }

    private ListAppender<ILoggingEvent> appender() {
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
