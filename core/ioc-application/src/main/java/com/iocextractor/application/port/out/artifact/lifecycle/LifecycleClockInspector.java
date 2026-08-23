package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleClockSnapshot;

/** Read-only view of durable/effective lifecycle clock confidence. */
@FunctionalInterface
public interface LifecycleClockInspector {

    /** Reads clock confidence without advancing the durable high-water mark. */
    LifecycleClockSnapshot inspect();
}
