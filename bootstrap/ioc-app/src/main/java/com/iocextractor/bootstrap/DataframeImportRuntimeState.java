package com.iocextractor.bootstrap;

import java.time.Instant;
import java.util.Objects;

/** Safely published, value-free lifecycle state for managed dataframe import. */
final class DataframeImportRuntimeState {

    private volatile Snapshot snapshot = new Snapshot(Phase.PENDING, null, null, null);

    void recovering(Instant at) {
        snapshot = new Snapshot(Phase.RECOVERING, Objects.requireNonNull(at, "at"), null, null);
    }

    void running(Instant at) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(Phase.RUNNING, current.recoveryStartedAt(),
                Objects.requireNonNull(at, "at"), null);
    }

    void degraded(String code) {
        Snapshot current = snapshot;
        if (current.phase() != Phase.FAILED && current.phase() != Phase.STOPPED) {
            snapshot = new Snapshot(Phase.DEGRADED, current.recoveryStartedAt(),
                    current.recoveryCompletedAt(), Objects.requireNonNull(code, "code"));
        }
    }

    void failed(Instant at, String code) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(Phase.FAILED, current.recoveryStartedAt(),
                Objects.requireNonNull(at, "at"), Objects.requireNonNull(code, "code"));
    }

    void stopped(Instant at) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(Phase.STOPPED, current.recoveryStartedAt(),
                Objects.requireNonNull(at, "at"), current.code());
    }

    boolean recoveryComplete() {
        return switch (snapshot.phase()) {
            case RUNNING, DEGRADED, STOPPED -> true;
            case PENDING, RECOVERING, FAILED -> false;
        };
    }

    Snapshot snapshot() {
        return snapshot;
    }

    enum Phase {
        PENDING,
        RECOVERING,
        RUNNING,
        DEGRADED,
        FAILED,
        STOPPED
    }

    record Snapshot(Phase phase,
                    Instant recoveryStartedAt,
                    Instant recoveryCompletedAt,
                    String code) {

        Snapshot {
            Objects.requireNonNull(phase, "phase");
        }
    }
}
