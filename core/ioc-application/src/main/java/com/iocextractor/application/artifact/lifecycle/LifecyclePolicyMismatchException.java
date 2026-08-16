package com.iocextractor.application.artifact.lifecycle;

/** Fail-closed startup error for an impossible one-way validity configuration. */
public final class LifecyclePolicyMismatchException extends IllegalStateException {

    public LifecyclePolicyMismatchException(String message) {
        super(message);
    }
}
