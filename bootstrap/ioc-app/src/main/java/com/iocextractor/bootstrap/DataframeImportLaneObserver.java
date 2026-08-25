package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.concurrent.KeyedSerialExecutorObserver;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Converts bounded import-lane failures into value-free runtime degradation. */
final class DataframeImportLaneObserver implements KeyedSerialExecutorObserver {

    private static final Logger log = LoggerFactory.getLogger(DataframeImportLaneObserver.class);

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
        Objects.requireNonNull(admission, "admission");
        observe(() -> LogEvents.warn(log)
                .action(EventAction.IMPORT_WORK_ADMISSION)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_IMPORT_WORK_KEY, admission.key().value())
                .field(LogField.IOC_IMPORT_QUEUE_DEPTH, admission.queuedDepth())
                .field(LogField.IOC_IMPORT_SHED_TO_RECONCILE, true)
                .message("managed import work shed to durable reconciliation")
                .log());
    }

    @Override
    public void dispatchRejected(WorkKey key,
                                 int abandonedWork,
                                 RejectedExecutionException failure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        degrade();
        observe(() -> LogEvents.error(log)
                .action(EventAction.IMPORT_WORK_DISPATCH)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_IMPORT_WORK_KEY, key.value())
                .field(LogField.IOC_IMPORT_ABANDONED_WORK, abandonedWork)
                .field(LogField.IOC_IMPORT_SHED_TO_RECONCILE, true)
                .field(LogField.ERROR_TYPE, failure.getClass().getName())
                .message("managed import work dispatch rejected")
                .log());
    }

    @Override
    public void failed(WorkKey key, RuntimeException failure) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof DataframeImportConsistencyException) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CONSISTENCY_FAILED.id());
            emit(ImportDiagnosticCodes.CONSISTENCY_FAILED);
            logFailure(key, failure);
            return;
        }
        degrade();
        logFailure(key, failure);
    }

    private void logFailure(WorkKey key, RuntimeException failure) {
        observe(() -> LogEvents.error(log)
                .action(EventAction.IMPORT_WORK_DISPATCH)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_IMPORT_WORK_KEY, key.value())
                .field(LogField.ERROR_TYPE, failure.getClass().getName())
                .message("managed import keyed work failed")
                .log());
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

    private void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Operational logging cannot alter lane state or reconciliation.
        }
    }
}
