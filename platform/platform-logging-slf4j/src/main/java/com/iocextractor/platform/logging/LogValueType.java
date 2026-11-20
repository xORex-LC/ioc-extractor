package com.iocextractor.platform.logging;

/**
 * Scalar wire representations supported by {@link LogEvent#field(LogFieldSpec, Object)}.
 * Null omits a value of any type; arrays, maps and floating-point values are unsupported.
 */
public enum LogValueType {
    /** CharSequence, Enum, Path or UUID, snapshotted using {@code toString()}. */
    STRING,
    /** Byte, Short, Integer or Long, emitted as a Long without parsing text. */
    LONG,
    /** Boolean values only; textual {@code "true"} is rejected. */
    BOOLEAN
}
