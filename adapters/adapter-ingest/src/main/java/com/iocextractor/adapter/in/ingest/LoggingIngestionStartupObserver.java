package com.iocextractor.adapter.in.ingest;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.IngestDiagnosticCodes;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Emits typed startup-recovery logs and owns final diagnostic delivery. */
public final class LoggingIngestionStartupObserver implements IngestionStartupObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingIngestionStartupObserver.class);

    private final DiagnosticSink diagnosticSink;
    private final DiagnosticFactory diagnostics;

    public LoggingIngestionStartupObserver(DiagnosticSink diagnosticSink, Clock clock) {
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.diagnostics = new DiagnosticFactory(Objects.requireNonNull(clock, "clock"));
    }

    @Override
    public void recoveryStarted(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        event(LogEvents.info(log), EventOutcome.UNKNOWN)
                .message("ingestion startup recovery started")
                .log();
    }

    @Override
    public void recoveryCompleted(Instant startedAt, Instant completedAt,
                                  int recoveredRuns, int recoveredSources) {
        event(LogEvents.info(log), EventOutcome.SUCCESS)
                .durationNanos(durationNanos(startedAt, completedAt))
                .field(LogField.IOC_INGEST_RECOVERED_RUNS, recoveredRuns)
                .field(LogField.IOC_INGEST_RECOVERED_SOURCES, recoveredSources)
                .message("ingestion startup recovery completed")
                .log();
    }

    @Override
    public void recoveryFailed(Instant startedAt, Instant failedAt, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        event(LogEvents.error(log), EventOutcome.FAILURE)
                .durationNanos(durationNanos(startedAt, failedAt))
                .field(LogField.ERROR_TYPE, failure.getClass().getName())
                .message("ingestion startup recovery failed")
                .log();
        emitFailureDiagnostic(failure);
    }

    private LogEvent event(LogEvent event, EventOutcome outcome) {
        return event.action(EventAction.INGEST_RECOVER).outcome(outcome);
    }

    private void emitFailureDiagnostic(RuntimeException failure) {
        if (failure instanceof DiagnosticException diagnosticFailure
                && diagnosticFailure.diagnostic().category() == DiagnosticCategory.INGEST) {
            if (diagnosticFailure.diagnostic().code() != IngestDiagnosticCodes.RECOVERY_FAILED) {
                diagnosticSink.emit(diagnosticFailure.diagnostic());
            }
            return;
        }
        Diagnostic diagnostic = diagnostics.create(IngestDiagnosticCodes.RECOVERY_FAILED)
                .with("source", "startup")
                .with("reason", failure.getClass().getSimpleName())
                .cause(failure)
                .build();
        diagnosticSink.emit(diagnostic);
    }

    private long durationNanos(Instant startedAt, Instant completedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        return Math.max(0L, Duration.between(startedAt, completedAt).toNanos());
    }
}
