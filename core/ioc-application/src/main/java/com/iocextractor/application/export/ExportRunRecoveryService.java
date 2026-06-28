package com.iocextractor.application.export;

import com.iocextractor.application.port.in.export.RecoverExportUseCase;
import com.iocextractor.application.port.out.export.ArtifactSliceWriter;
import com.iocextractor.application.port.out.export.ExportObserver;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.application.port.out.export.ExportRunLedger;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Forward-recovers incomplete export runs from durable ledger and local slice evidence.
 *
 * <p>Recovery never invokes the canonical snapshot reader. Valid manifest coverage is the only
 * source used to reconstruct terminal progress after a crash.
 */
public final class ExportRunRecoveryService implements RecoverExportUseCase {

    private final ExportRunLedger ledger;
    private final ArtifactSliceWriter sliceWriter;
    private final ExportProgressStore progressStore;
    private final ExportChangeDetector changeDetector;
    private final ExportObserver observer;
    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;

    /** Creates recovery with no-op diagnostics and operational events. */
    public ExportRunRecoveryService(ExportRunLedger ledger,
                                    ArtifactSliceWriter sliceWriter,
                                    ExportProgressStore progressStore,
                                    Clock clock) {
        this(ledger, sliceWriter, progressStore, new ExportChangeDetector(), NoopExportObserver.INSTANCE,
                NoopDiagnosticSink.INSTANCE, new DiagnosticFactory(clock), clock);
    }

    /** Creates recovery with explicit policies and event/diagnostic delivery. */
    public ExportRunRecoveryService(ExportRunLedger ledger,
                                    ArtifactSliceWriter sliceWriter,
                                    ExportProgressStore progressStore,
                                    ExportChangeDetector changeDetector,
                                    ExportObserver observer,
                                    DiagnosticSink diagnosticSink,
                                    DiagnosticFactory diagnosticFactory,
                                    Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sliceWriter = Objects.requireNonNull(sliceWriter, "sliceWriter");
        this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
        this.changeDetector = Objects.requireNonNull(changeDetector, "changeDetector");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnosticFactory = Objects.requireNonNull(diagnosticFactory, "diagnosticFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public int recoverIncomplete() {
        List<ExportRun> incomplete = ledger.findIncomplete();
        for (ExportRun run : incomplete) {
            observer.recovering(run);
            try {
                recover(run);
            } catch (RuntimeException failure) {
                throw recoveryException(run, failure);
            }
        }
        return incomplete.size();
    }

    private void recover(ExportRun run) {
        ExportRun current = run;
        if (current.status() == ExportRunStatus.STARTED) {
            current = recoverStarted(current);
            if (current.status() == ExportRunStatus.FAILED) {
                return;
            }
        }
        if (current.status() == ExportRunStatus.STAGED) {
            current = recoverStaged(current);
            if (current.status() == ExportRunStatus.FAILED) {
                return;
            }
        }
        if (current.status() == ExportRunStatus.AVAILABLE) {
            completeAvailable(current);
        }
    }

    private ExportRun recoverStarted(ExportRun run) {
        SliceInspection inspection = sliceWriter.inspect(run);
        return switch (inspection.state()) {
            case RECOVERABLE, STAGED -> recoverStartedCandidate(run, inspection);
            case AVAILABLE -> ledger.transition(run.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.STAGED, inspection.manifestSha256(), null);
            case MISSING, PARTIAL -> {
                sliceWriter.discardStaging(run);
                yield fail(run, "staging is incomplete: " + inspection.state());
            }
            case CORRUPT, CONFLICT -> fail(run, inspection.reason());
        };
    }

    /** Repeats the post-hash decision lost by a crash after complete staging. */
    private ExportRun recoverStartedCandidate(ExportRun run, SliceInspection inspection) {
        List<ExportProgress> previous = progressStore.findByProfile(run.profile());
        if (changeDetector.sameContent(inspection.manifest(), previous)) {
            sliceWriter.discardStaging(run);
            List<ExportProgress> skipped = changeDetector.skippedProgress(
                    inspection.manifest(), previous, clock.instant());
            ExportRun terminal = ledger.finish(run.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.SKIPPED, skipped);
            observer.completed(terminal);
            return terminal;
        }
        String manifestSha256 = inspection.manifestSha256();
        if (inspection.state() == SliceInspectionState.RECOVERABLE) {
            manifestSha256 = sliceWriter.recoverStaging(run).manifestSha256();
        }
        return ledger.transition(run.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, manifestSha256, null);
    }

    private ExportRun recoverStaged(ExportRun run) {
        SliceInspection inspection = sliceWriter.inspect(run);
        if (inspection.state() != SliceInspectionState.STAGED
                && inspection.state() != SliceInspectionState.AVAILABLE) {
            return fail(run, "staged checkpoint has incompatible filesystem state: "
                    + inspection.state() + reasonSuffix(inspection.reason()));
        }
        sliceWriter.makeAvailable(run);
        return ledger.transition(run.runId(), ExportRunStatus.STAGED,
                ExportRunStatus.AVAILABLE, null, null);
    }

    private void completeAvailable(ExportRun run) {
        SliceInspection inspection = sliceWriter.inspect(run);
        if (inspection.state() != SliceInspectionState.AVAILABLE) {
            fail(run, "available checkpoint has incompatible filesystem state: "
                    + inspection.state() + reasonSuffix(inspection.reason()));
            return;
        }
        List<ExportProgress> progress = changeDetector.completedProgress(
                inspection.manifest(), clock.instant());
        ExportRun terminal = ledger.finish(run.runId(), ExportRunStatus.AVAILABLE,
                ExportRunStatus.COMPLETED, progress);
        observer.completed(terminal);
    }

    private ExportRun fail(ExportRun run, String reason) {
        String detail = reason == null || reason.isBlank() ? "unrecoverable filesystem state" : reason;
        emitRecoveryFailure(run, detail, null);
        ExportRun failed = ledger.transition(run.runId(), run.status(),
                ExportRunStatus.FAILED, null, detail);
        observer.completed(failed);
        return failed;
    }

    private DiagnosticException recoveryException(ExportRun run, RuntimeException cause) {
        Diagnostic diagnostic = emitRecoveryFailure(run,
                Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()), cause);
        return new DiagnosticException(diagnostic);
    }

    private Diagnostic emitRecoveryFailure(ExportRun run, String reason, Throwable cause) {
        var builder = diagnosticFactory.create(ExportDiagnosticCodes.RECOVERY_FAILED)
                .with("runId", run.runId())
                .with("status", run.status().name())
                .with("reason", reason);
        if (cause != null) {
            builder.cause(cause);
        }
        Diagnostic diagnostic = builder.build();
        diagnosticSink.emit(diagnostic);
        return diagnostic;
    }

    private String reasonSuffix(String reason) {
        return reason == null ? "" : ": " + reason;
    }
}
