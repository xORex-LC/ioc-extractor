package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.RunLedger;

import java.util.Objects;

/**
 * Recovers per-file write-to-project crash windows recorded in the run ledger.
 */
public final class IngestRunRecoveryService {

    private final RunLedger runLedger;
    private final ArtifactProjection projection;

    public IngestRunRecoveryService(RunLedger runLedger, ArtifactProjection projection) {
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
        for (IngestRun run : runLedger.findIncompleteIngestRuns()) {
            recovered++;
            if (run.status() == IngestRunStatus.STARTED) {
                runLedger.markFailed(run.runId(), "startup recovery: run stopped before DB commit");
                continue;
            }
            if (run.status() == IngestRunStatus.DB_COMMITTED) {
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
