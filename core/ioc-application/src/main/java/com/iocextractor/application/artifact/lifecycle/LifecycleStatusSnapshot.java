package com.iocextractor.application.artifact.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only aggregate lifecycle status backed by canonical durable state. */
public record LifecycleStatusSnapshot(LifecycleControlState control,
                                      LifecycleClockSnapshot clock,
                                      List<LifecycleArtifactStatistics> artifacts,
                                      Optional<Instant> nearestDeadline,
                                      Optional<Instant> oldestDueAt,
                                      long pendingProjections,
                                      LifecycleReconcileCycleState latestCycleState,
                                      Optional<Instant> latestCycleStartedAt,
                                      Optional<Instant> latestCycleCompletedAt,
                                      long latestCycleExpired,
                                      Optional<String> latestFailureCode,
                                      Duration dueBacklogAge) {

    /** Copies aggregate state and validates non-negative counters. */
    public LifecycleStatusSnapshot {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(clock, "clock");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        nearestDeadline = Objects.requireNonNull(nearestDeadline, "nearestDeadline");
        oldestDueAt = Objects.requireNonNull(oldestDueAt, "oldestDueAt");
        Objects.requireNonNull(latestCycleState, "latestCycleState");
        latestCycleStartedAt = Objects.requireNonNull(latestCycleStartedAt, "latestCycleStartedAt");
        latestCycleCompletedAt = Objects.requireNonNull(latestCycleCompletedAt, "latestCycleCompletedAt");
        latestFailureCode = Objects.requireNonNull(latestFailureCode, "latestFailureCode");
        Objects.requireNonNull(dueBacklogAge, "dueBacklogAge");
        if (pendingProjections < 0 || latestCycleExpired < 0 || dueBacklogAge.isNegative()) {
            throw new IllegalArgumentException("Lifecycle status counters must not be negative");
        }
    }

    /** Returns the aggregate number of physically present rows already due. */
    public long dueRecords() {
        return artifacts.stream().mapToLong(LifecycleArtifactStatistics::due).sum();
    }

    /** Returns the aggregate number of retained historical lifecycles. */
    public long historyRecords() {
        return artifacts.stream().mapToLong(LifecycleArtifactStatistics::history).sum();
    }
}
