package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorObserver;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Converts bounded import-lane failures into value-free runtime degradation. */
final class DataframeImportLaneObserver implements KeyedSerialExecutorObserver {

    private final DataframeImportRuntimeState state;
    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory factory;
    private final Clock clock;

    DataframeImportLaneObserver(DataframeImportRuntimeState state,
                                DiagnosticSink diagnostics,
                                Clock clock) {
        this.state = Objects.requireNonNull(state, "state");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.factory = new DiagnosticFactory(clock);
    }

    @Override
    public void rejected(WorkAdmission admission) {
        degrade();
    }

    @Override
    public void dispatchRejected(WorkKey key,
                                 int abandonedWork,
                                 RejectedExecutionException failure) {
        degrade();
    }

    @Override
    public void failed(WorkKey key, RuntimeException failure) {
        if (failure instanceof DataframeImportConsistencyException) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CONSISTENCY_FAILED.id());
            emit(ImportDiagnosticCodes.CONSISTENCY_FAILED);
            return;
        }
        degrade();
    }

    private void degrade() {
        state.degraded(ImportDiagnosticCodes.PROCESSING_FAILED.id());
        emit(ImportDiagnosticCodes.PROCESSING_FAILED);
    }

    private void emit(ImportDiagnosticCodes code) {
        try {
            diagnostics.emit(factory.create(code).build());
        } catch (RuntimeException ignored) {
            // Observation cannot alter durable import recovery.
        }
    }
}
