package com.iocextractor.application.port.out.aggregation;

import com.iocextractor.application.aggregation.AggregationRun;

import java.util.List;

/**
 * Durable ledger for long-running aggregation/export sagas.
 */
public interface RunLedger {

    AggregationRun startAggregation(List<String> artifacts);

    void markDbCommitted(String runId);

    void markProjectionCompleted(String runId);

    void markCompleted(String runId);

    void markFailed(String runId, String reason);

    List<AggregationRun> findIncompleteAggregationRuns();
}
