package com.iocextractor.application.artifact.lifecycle;

/**
 * Injected UTC wall-clock boundary for durable lifecycle operations.
 *
 * <p>Runtime adapters may add high-water/clamp safety around the system clock.
 * Callers decide the operation boundary: a canonical transaction samples this
 * source exactly once after obtaining write ownership.
 */
@FunctionalInterface
public interface LifecycleTimeSource {

    /** Returns the current effective UTC time. */
    EffectiveTime now();
}
