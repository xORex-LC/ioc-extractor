package com.iocextractor.application.artifact.lifecycle;

/** Stable reason recorded when an active canonical lifecycle is closed. */
public enum LifecycleCloseReason {
    /** The stored validity boundary was reached. */
    EXPIRED,
    /** A legacy row was closed by explicit one-way activation. */
    LEGACY_ACTIVATION
}
