package com.iocextractor.application.port.out.artifact.lifecycle;

import com.iocextractor.application.artifact.lifecycle.LifecycleControlState;

/** Driven port for the singleton, one-way lifecycle activation state. */
public interface LifecycleControlStore {

    /** Returns the durable control state initialized by storage migration. */
    LifecycleControlState load();

    /**
     * Atomically replaces {@code expected} with {@code update}.
     *
     * <p>{@code update} must be the next legal state produced from
     * {@code expected}, including an exactly-one version increment.
     *
     * @return {@code true} when the expected version/state matched and was replaced
     */
    boolean compareAndSet(LifecycleControlState expected, LifecycleControlState update);
}
