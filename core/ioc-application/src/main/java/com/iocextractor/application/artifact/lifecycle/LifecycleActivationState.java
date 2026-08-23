package com.iocextractor.application.artifact.lifecycle;

/** Persisted one-way activation state for canonical record validity. */
public enum LifecycleActivationState {
    /** Existing behavior is preserved and lifecycle metadata is not authoritative. */
    DISABLED_COMPATIBLE,
    /** Explicit legacy-row activation is incomplete and must resume before readiness. */
    ACTIVATING,
    /** Lifecycle metadata and active-read filtering are authoritative. */
    ACTIVE
}
