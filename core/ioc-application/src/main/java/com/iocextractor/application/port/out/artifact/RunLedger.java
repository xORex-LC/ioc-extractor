package com.iocextractor.application.port.out.artifact;

import com.iocextractor.application.artifact.IngestRun;

import java.util.List;

/**
 * Durable ledger for per-file ingest write-to-project sagas.
 */
public interface RunLedger {

    IngestRun startIngest(String sourceKey, List<String> artifacts);

    void markDbCommitted(String runId);

    void markProjectionCompleted(String runId);

    void markCompleted(String runId);

    void markFailed(String runId, String reason);

    List<IngestRun> findIncompleteIngestRuns();
}
