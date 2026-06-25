package com.iocextractor.application.aggregation;

import com.iocextractor.application.port.out.aggregation.ArtifactProjection;
import com.iocextractor.application.port.out.aggregation.RunLedger;

import java.util.Objects;

/**
 * Recovers aggregation crash windows recorded in the run ledger.
 */
public final class AggregationRunRecoveryService {

    private final RunLedger runLedger;
    private final ArtifactProjection projection;

    public AggregationRunRecoveryService(RunLedger runLedger, ArtifactProjection projection) {
        this.runLedger = Objects.requireNonNull(runLedger, "runLedger");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    /**
     * Replays pending projection work and closes recoverable run-ledger rows.
     *
     * @return number of runs examined
     */
    public int recover() {
        int recovered = 0;
        for (AggregationRun run : runLedger.findIncompleteAggregationRuns()) {
            recovered++;
            if (run.status() == AggregationRunStatus.STARTED) {
                runLedger.markFailed(run.runId(), "startup recovery: run stopped before DB commit");
                continue;
            }
            if (run.status() == AggregationRunStatus.DB_COMMITTED) {
                for (String artifact : run.artifacts()) {
                    projection.project(artifact);
                }
                runLedger.markProjectionCompleted(run.runId());
            }
            runLedger.markCompleted(run.runId());
        }
        return recovered;
    }
}
