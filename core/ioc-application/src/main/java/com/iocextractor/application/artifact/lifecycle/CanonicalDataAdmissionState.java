package com.iocextractor.application.artifact.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Process-local publication barrier for canonical-data admission.
 *
 * <p>Durable SQLite state remains the recovery authority. This object only
 * prevents local driving adapters and schedulers from racing the common
 * admission sequence. One coordinator writes immutable snapshots; concurrent
 * health and scheduler readers observe them through a volatile reference.
 */
public final class CanonicalDataAdmissionState {

    private final Object listenerMonitor = new Object();
    private final List<Runnable> admittedListeners = new ArrayList<>();
    private volatile Snapshot snapshot = Snapshot.pending();

    /** Creates a compatibility gate already open for lifecycle-disabled callers. */
    public static CanonicalDataAdmissionState admittedCompatible(EffectiveTime effectiveTime) {
        CanonicalDataAdmissionState state = new CanonicalDataAdmissionState();
        state.admitted(new LifecycleAdmissionResult(
                LifecycleActivationState.DISABLED_COMPATIBLE,
                Objects.requireNonNull(effectiveTime, "effectiveTime"), 0, 0));
        return state;
    }

    /** Marks the beginning of the common admission sequence. */
    public void preparing() {
        snapshot = new Snapshot(Phase.PREPARING, null, null);
    }

    /**
     * Publishes successful admission and invokes registered admission callbacks.
     *
     * <p>A callback failure is propagated so the admission coordinator can fail
     * closed. The callback list is retained until every callback succeeds;
     * callbacks must therefore be idempotent and may be retried after recovery.
     */
    public void admitted(LifecycleAdmissionResult result) {
        Objects.requireNonNull(result, "result");
        List<Runnable> listeners;
        synchronized (listenerMonitor) {
            snapshot = new Snapshot(Phase.ADMITTED, result, null);
            listeners = List.copyOf(admittedListeners);
        }
        listeners.forEach(Runnable::run);
        synchronized (listenerMonitor) {
            admittedListeners.removeAll(listeners);
        }
    }

    /** Publishes a fail-closed admission outcome without retaining sensitive messages. */
    public void failed(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        snapshot = new Snapshot(Phase.FAILED, null, failure.getClass().getSimpleName());
    }

    /** Registers idempotent work that may start only after admission. */
    public void whenAdmitted(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean runNow;
        synchronized (listenerMonitor) {
            runNow = snapshot.phase() == Phase.ADMITTED;
            if (!runNow) {
                admittedListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    /** Returns the current immutable admission snapshot. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public enum Phase {
        PENDING,
        PREPARING,
        ADMITTED,
        FAILED
    }

    /** Aggregate state deliberately excludes IOC and source identities. */
    public record Snapshot(Phase phase,
                           LifecycleAdmissionResult result,
                           String failure) {

        public Snapshot {
            Objects.requireNonNull(phase, "phase");
            if (phase == Phase.ADMITTED && result == null) {
                throw new IllegalArgumentException("Admitted state requires a result");
            }
            if (phase != Phase.ADMITTED && result != null) {
                throw new IllegalArgumentException("Only admitted state may carry a result");
            }
            if (phase == Phase.FAILED && (failure == null || failure.isBlank())) {
                throw new IllegalArgumentException("Failed state requires a failure type");
            }
            if (phase != Phase.FAILED && failure != null) {
                throw new IllegalArgumentException("Only failed state may carry a failure type");
            }
        }

        private static Snapshot pending() {
            return new Snapshot(Phase.PENDING, null, null);
        }
    }
}
