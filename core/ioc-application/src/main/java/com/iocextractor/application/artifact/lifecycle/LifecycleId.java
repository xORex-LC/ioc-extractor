package com.iocextractor.application.artifact.lifecycle;

/**
 * Service-owned identity of one canonical record lifecycle.
 *
 * @param value positive, durable and never-reused identifier
 */
public record LifecycleId(long value) {

    /** Validates the durable identifier. */
    public LifecycleId {
        if (value <= 0) {
            throw new IllegalArgumentException("Lifecycle id must be positive");
        }
    }
}
