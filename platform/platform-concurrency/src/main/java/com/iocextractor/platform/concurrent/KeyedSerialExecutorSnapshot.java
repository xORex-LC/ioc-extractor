package com.iocextractor.platform.concurrent;

import java.util.List;
import java.util.Objects;

/** Immutable read-only view of in-memory keyed executor state. */
public record KeyedSerialExecutorSnapshot(List<KeyedWorkSnapshot> keys) {

    public KeyedSerialExecutorSnapshot {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }

    /** Returns an empty snapshot for executors that do not expose runtime state. */
    public static KeyedSerialExecutorSnapshot empty() {
        return new KeyedSerialExecutorSnapshot(List.of());
    }
}
