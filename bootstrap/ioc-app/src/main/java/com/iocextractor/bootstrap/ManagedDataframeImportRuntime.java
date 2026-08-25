package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.DataframeImportConsistencyException;
import com.iocextractor.application.dataframeimport.DataframeImportDetectionCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportDrainCoordinator;
import com.iocextractor.application.dataframeimport.DataframeImportRecoveryCoordinator;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.application.port.in.dataframeimport.RunDataframeImportRetentionUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportChangeSignalSource;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final DataframeImportRuntimeObserver observer;
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
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = new DataframeImportRuntimeObserver(diagnostics, clock);
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
        Instant startedAt = clock.instant();
        state.recovering(clock.instant());
        observer.recoveryStarted();
        RecoverDataframeImportsResult result;
        boolean recovered = false;
        try {
            result = recovery.recover(recoveryBatchSize);
            recovered = true;
        } finally {
            if (!recovered) {
                state.failed(clock.instant(), ImportDiagnosticCodes.PROCESSING_FAILED.id());
                observer.recoveryFailed(ImportDiagnosticCodes.PROCESSING_FAILED);
            }
        }
        if (result.contradictions() > 0) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CONSISTENCY_FAILED.id());
            observer.recoveryFailed(
                    ImportDiagnosticCodes.CONSISTENCY_FAILED,
                    DataframeImportConsistencyException.class.getName());
            throw new DataframeImportConsistencyException(
                    "Managed import recovery found contradictory durable evidence");
        }
        observer.recoveryCompleted(result, elapsedSince(startedAt));
        return result;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        boolean startingChangeSignals = false;
        try {
            reconcile();
            scheduler.scheduleWithFixedDelay(this::safeReconcile,
                    reconcileInterval.toMillis(), reconcileInterval.toMillis(), TimeUnit.MILLISECONDS);
            scheduler.scheduleWithFixedDelay(this::safeRetention,
                    retentionInterval.toMillis(), retentionInterval.toMillis(), TimeUnit.MILLISECONDS);
            startingChangeSignals = true;
            for (ImportChangeSignalSource signal : changeSignals) {
                signal.start(sourceId -> {
                    detection.nudge(sourceId);
                    drain.nudge();
                });
            }
            state.running(clock.instant());
        } catch (RuntimeException failure) {
            recordStartupFailure(startingChangeSignals, failure);
            throw closeAfterStartupFailure(failure);
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
            observer.recoveryFailed(
                    ImportDiagnosticCodes.PROCESSING_FAILED, failure.getClass().getName());
        }
    }

    private void reconcile() {
        detection.reconcile();
        drain.nudge();
    }

    private void safeRetention() {
        Instant startedAt = clock.instant();
        try {
            int retained = retention.retain(retentionBatchSize);
            observer.retentionCompleted(retained, elapsedSince(startedAt));
        } catch (RuntimeException failure) {
            state.degraded(ImportDiagnosticCodes.RETENTION_FAILED.id());
            observer.retentionFailed(failure.getClass().getName());
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

    private RuntimeException closeAfterStartupFailure(RuntimeException failure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private void recordStartupFailure(boolean startingChangeSignals, RuntimeException failure) {
        if (startingChangeSignals) {
            state.failed(clock.instant(), ImportDiagnosticCodes.CHANGE_SIGNAL_FAILED.id());
            observer.changeSignalFailed(failure.getClass().getName());
            return;
        }
        state.failed(clock.instant(), ImportDiagnosticCodes.PROCESSING_FAILED.id());
        observer.recoveryFailed(
                ImportDiagnosticCodes.PROCESSING_FAILED, failure.getClass().getName());
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

    private Duration elapsedSince(Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
