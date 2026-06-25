package com.iocextractor.application.artifact;

import com.iocextractor.application.port.out.artifact.RunLedger;

import java.util.List;

/**
 * Run-ledger implementation for storage modes without durable saga checkpoints.
 */
public final class NoopRunLedger implements RunLedger {

    @Override
    public IngestRun startIngest(String sourceKey, List<String> artifacts) {
        return new IngestRun("noop", sourceKey, IngestRunStatus.STARTED, artifacts, null, null, null);
    }

    @Override
    public void markDbCommitted(String runId) {
    }

    @Override
    public void markProjectionCompleted(String runId) {
    }

    @Override
    public void markCompleted(String runId) {
    }

    @Override
    public void markFailed(String runId, String reason) {
    }

    @Override
    public List<IngestRun> findIncompleteIngestRuns() {
        return List.of();
    }
}
