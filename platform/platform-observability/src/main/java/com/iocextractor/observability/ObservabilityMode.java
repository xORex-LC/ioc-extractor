package com.iocextractor.observability;

/**
 * Runtime observability mode.
 */
public enum ObservabilityMode {
    ONESHOT("oneshot"),
    DAEMON("daemon");

    private final String value;

    ObservabilityMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
