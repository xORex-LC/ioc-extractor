package com.iocextractor.application.port.in.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleHistoryRetentionResult;

/** Driving port for independent bounded lifecycle-history retention. */
@FunctionalInterface
public interface RunLifecycleHistoryRetentionUseCase {

    /** Purges one bounded batch per configured artifact. */
    LifecycleHistoryRetentionResult run();
}
