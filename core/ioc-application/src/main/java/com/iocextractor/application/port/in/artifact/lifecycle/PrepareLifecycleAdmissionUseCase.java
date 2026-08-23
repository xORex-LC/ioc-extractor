package com.iocextractor.application.port.in.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleAdmissionResult;

/** Driving port for the common canonical lifecycle admission barrier. */
@FunctionalInterface
public interface PrepareLifecycleAdmissionUseCase {

    /** Validates and converges durable lifecycle state before stateful work opens. */
    LifecycleAdmissionResult prepare();
}
