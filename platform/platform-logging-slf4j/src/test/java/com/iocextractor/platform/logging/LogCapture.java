package com.iocextractor.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns a test logger's appender, without stopping the shared provider. */
final class LogCapture implements AutoCloseable {
    static final LogFieldSpec TASK = new LogFieldSpec("collector.task.id", LogValueType.STRING);
    static final LogFieldSpec ITEMS = new LogFieldSpec("collector.items", LogValueType.LONG);
    static final LogFieldSpec RETRY = new LogFieldSpec("collector.retry", LogValueType.BOOLEAN);
    static final LogFieldSpec TOKEN = new LogFieldSpec("collector.auth.token", LogValueType.STRING);

    final Logger logger = (Logger) LoggerFactory.getLogger("test.logging-library." + UUID.randomUUID());
    final ListAppender<ILoggingEvent> output = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    LogCapture() {
        logger.setAdditive(false);
        logger.setLevel(Level.TRACE);
        output.setContext(logger.getLoggerContext());
        output.start();
        logger.addAppender(output);
    }

    static Map<String, Object> fields(ILoggingEvent event) {
        var result = new LinkedHashMap<String, Object>();
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(pair -> {
                if (result.put(pair.key, pair.value) != null) {
                    throw new AssertionError("Duplicate wire key: " + pair.key);
                }
            });
        }
        return result;
    }

    @Override
    public void close() {
        logger.detachAppender(output);
        output.stop();
        logger.setAdditive(true);
        logger.setLevel(null);
        MDC.clear();
    }
}
