package com.iocextractor.platform.logging.ecs;

import com.iocextractor.platform.logging.LogFieldSpec;
import com.iocextractor.platform.logging.LogValueType;

/**
 * Five scalar descriptors shared by the initial ECS profile (ECS 8.11 meanings).
 * The encoder owns version/runtime metadata and dataset. Type/category arrays
 * are outside this scalar API. Descriptors alone do not validate ECS semantics.
 */
public final class EcsFields {
    /** Service-defined operation name; prefer LogEvent.action for nonblank validation. */
    public static final LogFieldSpec EVENT_ACTION = new LogFieldSpec("event.action", LogValueType.STRING);
    /** Lower-case success/failure/unknown; omit when classification does not apply. */
    public static final LogFieldSpec EVENT_OUTCOME = new LogFieldSpec("event.outcome", LogValueType.STRING);
    /** Measured elapsed nanoseconds; prefer LogEvent.durationNanos to reject negatives. */
    public static final LogFieldSpec EVENT_DURATION = new LogFieldSpec("event.duration", LogValueType.LONG);
    /** Error class/type without a Throwable; a Boot Throwable record leaves error.* to its encoder. */
    public static final LogFieldSpec ERROR_TYPE = new LogFieldSpec("error.type", LogValueType.STRING);
    /** Full file path including its name; the producer owns resolution and disclosure. */
    public static final LogFieldSpec FILE_PATH = new LogFieldSpec("file.path", LogValueType.STRING);

    private EcsFields() { }
}
