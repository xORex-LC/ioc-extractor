package com.iocextractor.observability.logging;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builder for one SLF4J log call with scoped MDC event fields.
 */
public final class LogEvent {

    enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    private final Logger logger;
    private final Level level;
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private String message;

    LogEvent(Logger logger, Level level) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.level = Objects.requireNonNull(level, "level");
    }

    public LogEvent action(EventAction action) {
        return field(LogField.EVENT_ACTION, action.value());
    }

    public LogEvent outcome(EventOutcome outcome) {
        return field(LogField.EVENT_OUTCOME, outcome.value());
    }

    public LogEvent durationNanos(long durationNanos) {
        return field(LogField.EVENT_DURATION, durationNanos);
    }

    public LogEvent field(LogField field, Object value) {
        return field(field.key(), value);
    }

    public LogEvent field(String key, Object value) {
        fields.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    public LogEvent message(String message) {
        this.message = Objects.requireNonNull(message, "message");
        return this;
    }

    public void log() {
        log(null);
    }

    public void log(Throwable throwable) {
        if (!enabled()) {
            return;
        }
        try (var ignored = scope()) {
            write(throwable);
        }
    }

    private MdcScope scope() {
        var scope = MdcScope.open();
        fields.forEach(scope::put);
        return scope;
    }

    private boolean enabled() {
        return switch (level) {
            case TRACE -> logger.isTraceEnabled();
            case DEBUG -> logger.isDebugEnabled();
            case INFO -> logger.isInfoEnabled();
            case WARN -> logger.isWarnEnabled();
            case ERROR -> logger.isErrorEnabled();
        };
    }

    private void write(Throwable throwable) {
        var text = message != null ? message : "";
        switch (level) {
            case TRACE -> {
                if (throwable == null) {
                    logger.trace(text);
                } else {
                    logger.trace(text, throwable);
                }
            }
            case DEBUG -> {
                if (throwable == null) {
                    logger.debug(text);
                } else {
                    logger.debug(text, throwable);
                }
            }
            case INFO -> {
                if (throwable == null) {
                    logger.info(text);
                } else {
                    logger.info(text, throwable);
                }
            }
            case WARN -> {
                if (throwable == null) {
                    logger.warn(text);
                } else {
                    logger.warn(text, throwable);
                }
            }
            case ERROR -> {
                if (throwable == null) {
                    logger.error(text);
                } else {
                    logger.error(text, throwable);
                }
            }
        }
    }
}
