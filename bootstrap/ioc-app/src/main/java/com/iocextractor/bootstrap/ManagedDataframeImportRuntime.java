package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.application.dataframeimport.DataframeImportDetectionCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportDrainCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportRecoveryCoordinator;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.application.port.in.dataframeimport.RunDataframeImportRetentionUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns managed-import reconcile workers and their ordered graceful shutdown. */
final class ManagedDataframeImportRuntime implements DataframeImportRuntimeLifecycle {

    private final RecoverDataframeImportsUseCase recovery;
    private final DataframeImportDetectionCoordinator detection;
    private final DataframeImportDrainCoordinator drain;
    private final DataframeImportRecoveryCoordinator recoveryCoordinator;
    private final RunDataframeImportRetentionUseCase retention;
    private final List<ImportChangeSignalSource> changeSignals;
    private final KeyedSerialExecutor lanes;
    private final DataframeImportRuntimeState state;
    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;
    private final int recoveryBatchSize;
    private final int retentionBatchSize;
    private final Duration reconcileInterval;
    private final Duration retentionInterval;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    ManagedDataframeImportRuntime(
            RecoverDataframeImportsUseCase recovery,
            DataframeImportDetectionCoordinator detection,
            DataframeImportDrainCoordinator drain,
            DataframeImportRecoveryCoordinator recoveryCoordinator,
            RunDataframeImportRetentionUseCase retention,
            List<ImportChangeSignalSource> changeSignals,
            KeyedSerialExecutor lanes,
            DataframeImportRuntimeState state,
            DiagnosticSink diagnostics,
            Clock clock,
            int recoveryBatchSize,
            int retentionBatchSize,
            Duration reconcileInterval,
            Duration retentionInterval,
            Duration shutdownTimeout) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.detection = Objects.requireNonNull(detection, "detection");
        this.drain = Objects.requireNonNull(drain, "drain");
        this.recoveryCoordinator = Objects.requireNonNull(recoveryCoordinator, "recoveryCoordinator");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.changeSignals = List.copyOf(Objects.requireNonNull(changeSignals, "changeSignals"));
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.state = Objects.requireNonNull(state, "state");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticFactory = new DiagnosticFactory(clock);
        this.recoveryBatchSize = requirePositive(recoveryBatchSize, "recoveryBatchSize");
        this.retentionBatchSize = requirePositive(retentionBatchSize, "retentionBatchSize");
        this.reconcileInterval = requirePositive(reconcileInterval, "reconcileInterval");
        this.retentionInterval = requirePositive(retentionInterval, "retentionInterval");
        this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("dataframe-import-reconcile").unstarted(runnable));
    }

    @Override
    public RecoverDataframeImportsResult recoverBeforeIntake() {
        state.recovering(clock.instant());
        RecoverDataframeImportsResult result = recovery.recover(recoveryBatchSize);
        if (result.contradictions() > 0) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CONSISTENCY_FAILED.id());
            emit(ImportDiagnosticCodes.CONSISTENCY_FAILED);
            throw new DataframeImportConsistencyException(
                    "Managed import recovery found contradictory durable evidence");
        }
        return result;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcile();
            scheduler.scheduleWithFixedDelay(this::safeReconcile,
                    reconcileInterval.toMillis(), reconcileInterval.toMillis(), TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(this::safeRetention,
                    retentionInterval.toMillis(), retentionInterval.toMillis(), TimeUnit.MILLISECONDS);
            for (ImportChangeSignalSource signal : changeSignals) {
                signal.start(sourceId -> {
                    detection.nudge(sourceId);
                    drain.nudge();
                });
            }
            state.running(clock.instant());
        } catch (RuntimeException failure) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CHANGE_SIGNAL_FAILED.id());
            close();
            throw failure;
        }
    }

    @Override
    public boolean recoveryComplete() {
        return state.recoveryComplete();
    }

    DataframeImportRuntimeState state() {
        return state;
    }

    private void safeReconcile() {
        try {
            recoveryCoordinator.nudge();
            reconcile();
        } catch (RuntimeException failure) {
            state.degraded(ImportDiagnosticCodes.PROCESSING_FAILED.id());
            emit(ImportDiagnosticCodes.PROCESSING_FAILED);
        }
    }

    private void reconcile() {
        detection.reconcile();
        drain.nudge();
    }

    private void safeRetention() {
        try {
            retention.retain(retentionBatchSize);
        } catch (RuntimeException failure) {
            state.degraded(ImportDiagnosticCodes.RETENTION_FAILED.id());
            emit(ImportDiagnosticCodes.RETENTION_FAILED);
        }
    }

    private void emit(ImportDiagnosticCodes code) {
        try {
            diagnostics.emit(diagnosticFactory.create(code).build());
        } catch (RuntimeException ignored) {
            // Observation cannot alter durable import recovery.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        changeSignals.forEach(this::closeSignal);
        scheduler.shutdown();
        lanes.shutdown();
        awaitScheduler();
        awaitLanes();
        if (state.snapshot().phase() != DataframeImportRuntimeState.Phase.FAILED) {
            state.stopped(clock.instant());
        }
    }

    private void closeSignal(ImportChangeSignalSource signal) {
        try {
            signal.close();
        } catch (RuntimeException ignored) {
            // Poll/reconcile is already stopped and the process is shutting down.
        }
    }

    private void awaitScheduler() {
        try {
            if (!scheduler.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private void awaitLanes() {
        try {
            lanes.awaitTermination(shutdownTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isPositive()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
