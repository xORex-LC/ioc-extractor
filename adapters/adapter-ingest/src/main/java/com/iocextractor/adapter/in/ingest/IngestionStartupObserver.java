package com.iocextractor.adapter.in.ingest;

import java.time.Instant;

/** Operational observer for the recovery-before-intake startup barrier. */
public interface IngestionStartupObserver {

    /** Records entry into ordered startup recovery. */
    void recoveryStarted(Instant startedAt);

    /** Records successful recovery and the work observed before intake opens. */
    void recoveryCompleted(Instant startedAt, Instant completedAt,
                           int recoveredRuns, int recoveredSources);

    /** Records a fail-closed recovery outcome. */
    void recoveryFailed(Instant startedAt, Instant failedAt, RuntimeException failure);
}
