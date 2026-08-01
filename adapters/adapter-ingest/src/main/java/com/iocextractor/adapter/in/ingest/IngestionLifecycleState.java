package com.iocextractor.adapter.in.ingest;

import java.time.Instant;
import java.util.Objects;

/** Thread-safe operational state of daemon ingestion startup and intake. */
public final class IngestionLifecycleState {

    private volatile Snapshot snapshot = Snapshot.pending();

    /** Marks the beginning of the ordered recovery barrier. */
    public void recoveryStarted(Instant startedAt) {
        snapshot = new Snapshot(Phase.RECOVERING, Objects.requireNonNull(startedAt, "startedAt"),
                null, 0, 0, null);
    }

    /** Marks successful recovery and intake activation. */
    public void running(Instant completedAt, int recoveredRuns, int recoveredSources) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(Phase.RUNNING, current.recoveryStartedAt(),
                Objects.requireNonNull(completedAt, "completedAt"),
                recoveredRuns, recoveredSources, null);
    }

    /** Marks a fail-closed startup outcome. */
    public void failed(Instant failedAt, RuntimeException failure) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(Phase.FAILED, current.recoveryStartedAt(),
                Objects.requireNonNull(failedAt, "failedAt"),
                current.recoveredRuns(), current.recoveredSources(), failureSummary(failure));
    }

    /** Returns the current immutable snapshot. */
    public Snapshot snapshot() {
        return snapshot;
    }

    private String failureSummary(RuntimeException failure) {
        return Objects.requireNonNull(failure, "failure").getClass().getSimpleName();
    }

    /** Coarse startup/intake lifecycle visible through health. */
    public enum Phase {
        PENDING,
        RECOVERING,
        RUNNING,
        FAILED
    }

    /** Immutable operational snapshot; source keys are deliberately absent. */
    public record Snapshot(Phase phase,
                           Instant recoveryStartedAt,
                           Instant recoveryCompletedAt,
                           int recoveredRuns,
                           int recoveredSources,
                           String failure) {

        public Snapshot {
            phase = Objects.requireNonNull(phase, "phase");
            if (recoveredRuns < 0 || recoveredSources < 0) {
                throw new IllegalArgumentException("recovery counts must not be negative");
            }
        }

        private static Snapshot pending() {
            return new Snapshot(Phase.PENDING, null, null, 0, 0, null);
        }
    }
}
