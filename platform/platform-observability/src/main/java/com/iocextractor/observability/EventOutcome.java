package com.iocextractor.observability;

/**
 * Stable {@code event.outcome} values.
 */
public enum EventOutcome {
    SUCCESS("success"),
    FAILURE("failure"),
    RETRY("retry"),
    SKIPPED("skipped"),
    UNKNOWN("unknown");

    private final String value;

    EventOutcome(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
