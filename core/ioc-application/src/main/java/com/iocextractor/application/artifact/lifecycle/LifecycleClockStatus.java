package com.iocextractor.application.artifact.lifecycle;

/** Operational confidence in the effective UTC lifecycle clock. */
public enum LifecycleClockStatus {
    SAFE,
    CLAMPED,
    UNSAFE
}
