package com.iocextractor.application.artifact.lifecycle;

/** Durable terminal/running state of the latest expiration reconciliation cycle. */
public enum LifecycleReconcileCycleState {
    NEVER_RUN,
    STARTED,
    COMPLETED,
    FAILED
}
