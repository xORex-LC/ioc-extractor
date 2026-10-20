package com.iocextractor.platform.logging;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/** Level factories for typed records; each call returns a new mutable event. */
public final class LogEvents {
    private LogEvents() { }

    /**
     * Begins a TRACE record. Field checks still run when TRACE is disabled.
     * @param logger application-selected SLF4J logger
     * @return a new event
     * @throws NullPointerException if logger is null
     */
    public static LogEvent trace(Logger logger) {
        return new LogEvent(logger, Level.TRACE);
    }

    /**
     * Begins a DEBUG record. Field checks still run when DEBUG is disabled.
     * @param logger application-selected SLF4J logger
     * @return a new event
     * @throws NullPointerException if logger is null
     */
    public static LogEvent debug(Logger logger) {
        return new LogEvent(logger, Level.DEBUG);
    }

    /**
     * Begins an INFO record. Field checks still run when INFO is disabled.
     * @param logger application-selected SLF4J logger
     * @return a new event
     * @throws NullPointerException if logger is null
     */
    public static LogEvent info(Logger logger) {
        return new LogEvent(logger, Level.INFO);
    }

    /**
     * Begins a WARN record. Field checks still run when WARN is disabled.
     * @param logger application-selected SLF4J logger
     * @return a new event
     * @throws NullPointerException if logger is null
     */
    public static LogEvent warn(Logger logger) {
        return new LogEvent(logger, Level.WARN);
    }

    /**
     * Begins an ERROR record. Field checks still run when ERROR is disabled.
     * @param logger application-selected SLF4J logger
     * @return a new event
     * @throws NullPointerException if logger is null
     */
    public static LogEvent error(Logger logger) {
        return new LogEvent(logger, Level.ERROR);
    }
}
