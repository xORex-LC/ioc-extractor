package com.iocextractor.observability.logging;

import org.slf4j.Logger;

/**
 * Factory methods for operational log events.
 */
public final class LogEvents {

    private LogEvents() {
    }

    public static LogEvent trace(Logger logger) {
        return new LogEvent(logger, LogEvent.Level.TRACE);
    }

    public static LogEvent debug(Logger logger) {
        return new LogEvent(logger, LogEvent.Level.DEBUG);
    }

    public static LogEvent info(Logger logger) {
        return new LogEvent(logger, LogEvent.Level.INFO);
    }

    public static LogEvent warn(Logger logger) {
        return new LogEvent(logger, LogEvent.Level.WARN);
    }

    public static LogEvent error(Logger logger) {
        return new LogEvent(logger, LogEvent.Level.ERROR);
    }
}
