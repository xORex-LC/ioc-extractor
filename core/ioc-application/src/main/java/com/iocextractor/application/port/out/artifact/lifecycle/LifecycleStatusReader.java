package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleStatusSnapshot;

/** Driven read-only port for aggregate lifecycle health facts. */
@FunctionalInterface
public interface LifecycleStatusReader {

    /** Reads counts, deadlines, cycle, projection and clock facts without mutation. */
    LifecycleStatusSnapshot read();
}
