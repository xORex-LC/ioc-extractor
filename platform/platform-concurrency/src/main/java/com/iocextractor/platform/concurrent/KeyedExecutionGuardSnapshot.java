package com.iocextractor.platform.concurrent;

/**
 * Approximate aggregate runtime state of a synchronous keyed execution guard.
 * Counts can reflect different instants during concurrent entry or exit.
 *
 * @param activeKeys keys with executing or waiting callers
 * @param executing keys currently executing guarded work, counting nested same-key calls once
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
