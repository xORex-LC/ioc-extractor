package com.iocextractor.application.artifact.lifecycle;

/** Durable identity of one expiration reconciliation pass. */
public record LifecycleReconcileCycleId(long value) {

    public LifecycleReconcileCycleId {
        if (value <= 0) {
            throw new IllegalArgumentException("Lifecycle reconciliation cycle ID must be positive");
        }
    }
}
