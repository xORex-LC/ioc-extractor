package com.iocextractor.platform.logging;

import com.iocextractor.platform.logging.ecs.EcsFields;
import com.iocextractor.platform.logging.ecs.EventOutcome;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a log record with typed scalar fields and optional ECS conveniences.
 *
 * <p>Create an event through {@link LogEvents}. Fields are identified by key,
 * with one declared type per event even after null omission. Validation occurs
 * before level filtering; a disabled level does not excuse a wrong field type.
 * Generic {@link #field} checks representation, not ECS meaning or schema ownership.
 *
 * <p>On emission, event-local values hide exact same-key MDC strings temporarily.
 * Nested parent/child path conflicts and encoder-owned fields remain service
 * schema responsibilities. Messages, fields, ambient context and Throwable are
 * not automatically sanitized. Delivery and JSON encoding belong to the provider.
 *
 * <p>This mutable builder is not thread-safe. Calling {@code log()} twice makes
 * two attempts; no durable or exactly-once delivery is promised.
 */
public final class LogEvent {
    private final Logger logger;
    private final Level level;
    private final Map<String, FieldValue> values = new LinkedHashMap<>();
    private String message = "";

    LogEvent(Logger logger, Level level) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.level = Objects.requireNonNull(level, "level");
    }

    /**
     * Supplies a scalar value or omits its emitted value when null is supplied.
     * Equivalent declarations replace one value. A conflicting type for the same
     * key fails, including after first-null or later-null calls. Rejected calls
     * leave the previous value and declaration intact.
     *
     * @param field immutable field declaration
     * @param value supported scalar input, or null to omit this event-local value
     * @return this event
     * @throws NullPointerException if field is null
     * @throws IllegalArgumentException if the declaration conflicts or value has
     *         an unsupported representation; messages do not include the value
     */
    public LogEvent field(LogFieldSpec field, Object value) {
        Objects.requireNonNull(field, "field");
        var prior = values.get(field.key());
        if (prior != null && prior.field().valueType() != field.valueType()) {
            throw new IllegalArgumentException("Field " + field.key() + " already declares "
                    + prior.field().valueType() + "; cannot declare " + field.valueType());
        }
        var normalized = LogValueNormalizer.normalize(field, value);
        values.put(field.key(), new FieldValue(field, normalized));
        return this;
    }

    /**
     * Sets ECS event.action to a nonblank service-defined action without trimming it.
     *
     * @param action service wire vocabulary, for example {@code collect_complete}
     * @return this event
     * @throws NullPointerException if action is null
     * @throws IllegalArgumentException if action is blank
     */
    public LogEvent action(String action) {
        Objects.requireNonNull(action, "action");
        if (action.isBlank()) {
            throw new IllegalArgumentException("event.action must not be blank");
        }
        return field(EcsFields.EVENT_ACTION, action);
    }

    /**
     * Sets ECS event.outcome using its lower-case wire vocabulary. Omit the field
     * when no success/failure classification applies; UNKNOWN is for an applicable
     * outcome that cannot be determined.
     *
     * @param outcome supported ECS outcome
     * @return this event
     * @throws NullPointerException if outcome is null
     */
    public LogEvent outcome(EventOutcome outcome) {
        return field(EcsFields.EVENT_OUTCOME, Objects.requireNonNull(outcome, "outcome").value());
    }

    /**
     * Sets measured elapsed time in nanoseconds, without unit conversion.
     *
     * @param durationNanos elapsed nanoseconds, from zero through Long.MAX_VALUE
     * @return this event
     * @throws IllegalArgumentException if durationNanos is negative
     */
    public LogEvent durationNanos(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("event.duration must be nonnegative nanoseconds");
        }
        return field(EcsFields.EVENT_DURATION, durationNanos);
    }

    /**
     * Sets the human-readable text. The default is an empty string.
     *
     * @param message text already suitable for disclosure at the chosen level
     * @return this event
     * @throws NullPointerException if message is null
     */
    public LogEvent message(String message) {
        this.message = Objects.requireNonNull(message, "message");
        return this;
    }

    /** Emits without a Throwable, subject to the provider's level filter. */
    public void log() {
        log(null);
    }

    /**
     * Emits with an optional cause, restoring shadowed MDC keys afterwards.
     * Provider/builder failures propagate; appender acceptance need not mean that
     * an encoder wrote bytes. With Boot ECS and a non-null cause, let the encoder
     * own error.* instead of also supplying explicit error fields or MDC keys.
     *
     * @param cause exception to pass through unchanged, or null for no cause
     */
    public void log(Throwable cause) {
        if (!logger.isEnabledForLevel(level)) {
            return;
        }
        try (var collision = MdcScope.open()) {
            for (var value : values.values()) {
                if (value.value() != null) {
                    collision.hide(value.field());
                }
            }
            var builder = logger.atLevel(level);
            for (var value : values.values()) {
                if (value.value() != null) {
                    builder = builder.addKeyValue(value.field().key(), value.value());
                }
            }
            if (cause != null) {
                builder = builder.setCause(cause);
            }
            builder.log(message);
        }
    }

    private record FieldValue(LogFieldSpec field, Object value) { }
}
