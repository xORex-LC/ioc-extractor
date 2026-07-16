package com.iocextractor.observability.logging;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.MdcScope;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builder for one SLF4J log call with typed event-local structured fields. */
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
    private final Map<LogField, Object> fields = new LinkedHashMap<>();
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
        Objects.requireNonNull(field, "field");
        var normalized = LogValueNormalizer.normalize(field, value);
        if (normalized == null) {
            fields.remove(field);
        } else {
            fields.put(field, normalized);
        }
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
        try (var ignored = collisionScope()) {
            write(builder(), throwable);
        }
    }

    private MdcScope collisionScope() {
        var scope = MdcScope.open();
        fields.keySet().forEach(scope::hide);
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

    private LoggingEventBuilder builder() {
        return switch (level) {
            case TRACE -> logger.atTrace();
            case DEBUG -> logger.atDebug();
            case INFO -> logger.atInfo();
            case WARN -> logger.atWarn();
            case ERROR -> logger.atError();
        };
    }

    private void write(LoggingEventBuilder builder, Throwable throwable) {
        fields.forEach((field, value) -> builder.addKeyValue(field.key(), value));
        if (throwable != null) {
            builder.setCause(throwable);
        }
        builder.log(message != null ? message : "");
    }
}
