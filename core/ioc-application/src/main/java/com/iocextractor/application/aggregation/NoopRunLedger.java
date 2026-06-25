package com.iocextractor.application.aggregation;

import com.iocextractor.application.port.out.aggregation.RunLedger;

import java.util.List;

/**
 * Run-ledger implementation for storage modes without durable saga checkpoints.
 */
public final class NoopRunLedger implements RunLedger {

    @Override
    public AggregationRun startAggregation(List<String> artifacts) {
        return new AggregationRun("noop", AggregationRunStatus.STARTED, artifacts, null, null, null);
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
    public List<AggregationRun> findIncompleteAggregationRuns() {
        return List.of();
    }
}
