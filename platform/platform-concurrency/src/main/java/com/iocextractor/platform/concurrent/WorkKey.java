package com.iocextractor.platform.concurrent;

/** Stable key used to serialize related in-memory work. */
public record WorkKey(String value) {

    public WorkKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    /** Creates a work key from text. */
    public static WorkKey of(String value) {
        return new WorkKey(value);
    }
}
