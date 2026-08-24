package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;

import java.util.List;
import java.util.Objects;

/**
 * Coalesces poll and watch triggers into one serial lane per import source.
 * Rejected or lost hints remain safe because {@link #reconcile()} submits a
 * complete listing for every configured source.
 */
public final class DataframeImportDetectionCoordinator {

    private static final String WORK_PREFIX = "dataframe-import-detect:";

    private final List<ImportSourceId> sourceIds;
    private final DataframeImportDetectionService detector;
    private final KeyedSerialExecutor executor;

    /** Creates a coordinator over an immutable source catalog snapshot. */
    public DataframeImportDetectionCoordinator(List<ImportSourceId> sourceIds,
                                               DataframeImportDetectionService detector,
                                               KeyedSerialExecutor executor) {
        this.sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        this.detector = Objects.requireNonNull(detector, "detector");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Submits one loss-tolerant source-change hint. */
    public WorkAdmission nudge(ImportSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!sourceIds.contains(sourceId)) {
            throw new IllegalArgumentException("Unknown dataframe import source: " + sourceId.value());
        }
        return executor.submit(workKey(sourceId), () -> detector.detect(sourceId));
    }

    /** Submits the correctness backstop for every source. */
    public void reconcile() {
        sourceIds.forEach(this::nudge);
    }

    private WorkKey workKey(ImportSourceId sourceId) {
        return new WorkKey(WORK_PREFIX + sourceId.value());
    }
}
