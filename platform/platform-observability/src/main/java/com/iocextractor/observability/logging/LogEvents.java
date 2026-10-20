package com.iocextractor.observability.logging;

import org.slf4j.Logger;

/**
 * IOC catalog facades over shared logging factories; the caller selects the logger.
 */
public final class LogEvents {

    private LogEvents() {
    }

    /**
     * Begins an IOC TRACE record using the shared builder.
     * @param logger producer's logger, preserving its category
     * @return a new event
     */
    public static LogEvent trace(Logger logger) {
        return new LogEvent(com.iocextractor.platform.logging.LogEvents.trace(logger));
    }

    /**
     * Begins an IOC DEBUG record using the shared builder.
     * @param logger producer's logger, preserving its category
     * @return a new event
     */
    public static LogEvent debug(Logger logger) {
        return new LogEvent(com.iocextractor.platform.logging.LogEvents.debug(logger));
    }

    /**
     * Begins an IOC INFO record using the shared builder.
     * @param logger producer's logger, preserving its category
     * @return a new event
     */
    public static LogEvent info(Logger logger) {
        return new LogEvent(com.iocextractor.platform.logging.LogEvents.info(logger));
    }

    /**
     * Begins an IOC WARN record using the shared builder.
     * @param logger producer's logger, preserving its category
     * @return a new event
     */
    public static LogEvent warn(Logger logger) {
        return new LogEvent(com.iocextractor.platform.logging.LogEvents.warn(logger));
    }

    /**
     * Begins an IOC ERROR record using the shared builder.
     * @param logger producer's logger, preserving its category
     * @return a new event
     */
    public static LogEvent error(Logger logger) {
        return new LogEvent(com.iocextractor.platform.logging.LogEvents.error(logger));
    }
}
