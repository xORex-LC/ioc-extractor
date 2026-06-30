package com.iocextractor.platform.concurrent;

/** Observer that intentionally ignores keyed executor telemetry. */
public enum NoopKeyedSerialExecutorObserver implements KeyedSerialExecutorObserver {
    INSTANCE
}
