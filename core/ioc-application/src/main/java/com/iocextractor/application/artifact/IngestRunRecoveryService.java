package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.ArtifactProjection;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.RunLedger;
import com.iocextractor.diagnostics.sink.DiagnosticSink;

import java.util.Objects;

/**
 * Recovers per-file write-to-project crash windows recorded in the run ledger.
 */
public final class IngestRunRecoveryService {

    private final RunLedger runLedger;
    private final ArtifactProjection projection;
    private final DiagnosticSink diagnosticSink;

    public IngestRunRecoveryService(RunLedger runLedger,
                                    ArtifactProjection projection,
                                    DiagnosticSink diagnosticSink) {
        this.runLedger = Objects.requireNonNull(runLedger, "runLedger");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
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
                    projection.project(new ArtifactProjectionCommand(run.runId(), artifact))
                            .diagnostics()
                            .forEach(diagnosticSink::emit);
                }
                runLedger.markProjectionCompleted(run.runId());
            }
            runLedger.markCompleted(run.runId());
        }
        return recovered;
    }
}
