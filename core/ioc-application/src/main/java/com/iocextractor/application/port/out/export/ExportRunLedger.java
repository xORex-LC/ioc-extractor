package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;

import java.util.List;
import java.util.Optional;

/**
 * Durable compare-and-set ledger for the formation-only export saga.
 */
public interface ExportRunLedger {

    /** Attempts to acquire global export single-flight with a STARTED run. */
    Optional<ExportRun> tryStart(ExportRun startedRun);

    /** Advances one non-terminal checkpoint when the current status equals {@code expected}. */
    ExportRun transition(String runId,
                         ExportRunStatus expected,
                         ExportRunStatus next,
                         String manifestSha256,
                         String reason);

    /**
     * Atomically writes per-artifact progress and advances a run to COMPLETED or SKIPPED.
     */
    ExportRun finish(String runId,
                     ExportRunStatus expected,
                     ExportRunStatus terminal,
                     List<ExportProgress> progress);

    Optional<ExportRun> find(String runId);

    List<ExportRun> findIncomplete();
}
