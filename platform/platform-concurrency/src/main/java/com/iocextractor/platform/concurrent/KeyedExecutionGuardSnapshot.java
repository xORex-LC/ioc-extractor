package com.iocextractor.platform.concurrent;

/**
 * Aggregate runtime state of a synchronous keyed execution guard.
 *
 * @param activeKeys keys with executing or waiting callers
 * @param executing executions currently inside their guarded work
 * @param waiting callers waiting to enter guarded work
 */
public record KeyedExecutionGuardSnapshot(int activeKeys, int executing, int waiting) {

    public KeyedExecutionGuardSnapshot {
        if (activeKeys < 0 || executing < 0 || waiting < 0) {
            throw new IllegalArgumentException("guard snapshot counts must not be negative");
        }
    }

    /** Returns the idle state. */
    public static KeyedExecutionGuardSnapshot empty() {
        return new KeyedExecutionGuardSnapshot(0, 0, 0);
    }
}
