package com.iocextractor.platform.logging.ecs;

/**
 * ECS outcome from the producer's perspective. Omit the field if success/failure
 * does not apply; a skipped operation is not automatically UNKNOWN.
 */
public enum EventOutcome {
    /** The applicable operation succeeded. */
    SUCCESS("success"),
    /** The applicable operation failed. */
    FAILURE("failure"),
    /** The applicable operation's outcome cannot be determined. */
    UNKNOWN("unknown");

    private final String value;

    EventOutcome(String value) {
        this.value = value;
    }

    /**
     * Returns the exact lower-case ECS value; enum.toString() retains its Java name.
     * @return success, failure or unknown
     */
    public String value() {
        return value;
    }
}
