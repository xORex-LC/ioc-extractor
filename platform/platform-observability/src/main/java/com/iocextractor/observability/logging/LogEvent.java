package com.iocextractor.observability.logging;

import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;

/**
 * Binds the IOC field and action catalogs to the shared logging builder.
 * The delegate owns validation, typed values, MDC collisions and emission.
 */
public final class LogEvent {
    private final com.iocextractor.platform.logging.LogEvent delegate;

    LogEvent(com.iocextractor.platform.logging.LogEvent delegate) {
        this.delegate = delegate;
    }

    /**
     * Adds the IOC action's stable wire value.
     * @param action catalogued operation
     * @return this event
     */
    public LogEvent action(EventAction action) {
        delegate.action(action.value());
        return this;
    }

    /**
     * Adds the shared ECS success, failure or unknown outcome.
     * @param outcome observed operation outcome
     * @return this event
     */
    public LogEvent outcome(EventOutcome outcome) {
        delegate.outcome(outcome.ecs());
        return this;
    }

    /**
     * Adds a non-negative ECS duration in nanoseconds.
     * @param durationNanos elapsed time
     * @return this event
     * @throws IllegalArgumentException if the duration is negative
     */
    public LogEvent durationNanos(long durationNanos) {
        delegate.durationNanos(durationNanos);
        return this;
    }

    /**
     * Adds a catalogued scalar using the shared representation checks. Null omits
     * the event-local value; it does not hide an ambient MDC value.
     * @param field IOC field declaration
     * @param value supported scalar, or null for omission
     * @return this event
     * @throws IllegalArgumentException if value does not match the declaration
     */
    public LogEvent field(LogField field, Object value) {
        delegate.field(field.spec(), value);
        return this;
    }

    /**
     * Sets the human-readable message without changing structured fields.
     * @param message non-null message
     * @return this event
     */
    public LogEvent message(String message) {
        delegate.message(message);
        return this;
    }

    /** Attempts one log call at the selected level, without a Throwable. */
    public void log() {
        delegate.log();
    }

    /**
     * Attempts one log call, temporarily hiding exact MDC collisions. A Throwable
     * record lets the backend supply error.*; do not also add error.type.
     * @param throwable optional cause; null emits without exception metadata
     */
    public void log(Throwable throwable) {
        delegate.log(throwable);
    }
}
