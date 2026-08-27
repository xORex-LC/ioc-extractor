package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
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
import java.util.Objects;

/** Emits aggregate managed-import runtime events without exposing delivery values. */
final class DataframeImportRuntimeObserver {

    private static final Logger log = LoggerFactory.getLogger(DataframeImportRuntimeObserver.class);

    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory diagnosticFactory;

    DataframeImportRuntimeObserver(DiagnosticSink diagnostics, Clock clock) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.diagnosticFactory = new DiagnosticFactory(Objects.requireNonNull(clock, "clock"));
    }

    void recoveryStarted() {
        observe(() -> LogEvents.info(log)
                .action(EventAction.IMPORT_RECOVER)
                .outcome(EventOutcome.UNKNOWN)
                .message("managed dataframe import startup recovery started")
                .log());
    }

    void recoveryCompleted(RecoverDataframeImportsResult result, Duration duration) {
        observe(() -> recoveryEvent(LogEvents.info(log), result, EventOutcome.SUCCESS)
                .durationNanos(duration.toNanos())
                .message("managed dataframe import startup recovery completed")
                .log());
    }

    void recoveryFailed(ImportDiagnosticCodes code, String errorType) {
        failure(EventAction.IMPORT_RECOVER, code, errorType,
                "managed dataframe import recovery failed");
    }

    void recoveryFailed(ImportDiagnosticCodes code) {
        failure(EventAction.IMPORT_RECOVER, code, null,
                "managed dataframe import recovery failed");
    }

    void retentionCompleted(int retained, Duration duration) {
        if (retained == 0) {
            return;
        }
        observe(() -> LogEvents.info(log)
                .action(EventAction.IMPORT_RETENTION)
                .outcome(EventOutcome.SUCCESS)
                .durationNanos(duration.toNanos())
                .field(LogField.IOC_IMPORT_RETAINED, retained)
                .message("managed dataframe import retention completed")
                .log());
    }

    void retentionFailed(ImportDiagnosticCodes code, String errorType) {
        failure(EventAction.IMPORT_RETENTION, code,
                errorType, "managed dataframe import retention failed");
    }

    void changeSignalFailed(String errorType) {
        failure(EventAction.IMPORT_CHANGE_SIGNAL, ImportDiagnosticCodes.CHANGE_SIGNAL_FAILED,
                errorType, "managed dataframe import change notification unavailable");
    }

    private LogEvent recoveryEvent(LogEvent event,
                                   RecoverDataframeImportsResult result,
                                   EventOutcome outcome) {
        return event.action(EventAction.IMPORT_RECOVER)
                .outcome(outcome)
                .field(LogField.IOC_IMPORT_RECOVERY_EXAMINED, result.examined())
                .field(LogField.IOC_IMPORT_RECOVERY_ADVANCED, result.advanced())
                .field(LogField.IOC_IMPORT_RECOVERY_CONTRADICTIONS, result.contradictions());
    }

    private void failure(EventAction action,
                         ImportDiagnosticCodes code,
                         String errorType,
                         String message) {
        observe(() -> diagnostics.emit(diagnosticFactory.create(code).build()));
        observe(() -> LogEvents.error(log)
                .action(action)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.ERROR_TYPE, errorType)
                .message(message)
                .log());
    }

    private void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Operational observation must not alter durable import behavior.
        }
    }
}
