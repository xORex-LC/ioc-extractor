package com.iocextractor.bootstrap;

import com.iocextractor.application.dataframeimport.contract.DelimitedInputReadException;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
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
import java.util.Optional;
import java.util.Set;

/** Translates durable managed-import checkpoints into value-free ECS events. */
final class LoggingDataframeImportObserver implements DataframeImportObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingDataframeImportObserver.class);

    private final DiagnosticSink diagnostics;
    private final DiagnosticFactory diagnosticFactory;
    private final Clock clock;

    LoggingDataframeImportObserver(DiagnosticSink diagnostics, Clock clock) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.diagnosticFactory = new DiagnosticFactory(clock);
    }

    @Override
    public void deliveryDetected(ImportDelivery delivery) {
        event(LogEvents.info(log), EventAction.IMPORT_START, delivery, EventOutcome.SUCCESS)
                .message("managed dataframe import delivery admitted")
                .log();
    }

    @Override
    public void claimCompleted(ImportDelivery delivery, Duration duration) {
        event(LogEvents.debug(log), EventAction.IMPORT_CLAIM, delivery, EventOutcome.SUCCESS)
                .durationNanos(duration.toNanos())
                .message("managed dataframe import snapshot claimed")
                .log();
    }

    @Override
    public void stagingCompleted(ImportDelivery delivery, Duration duration) {
        event(LogEvents.debug(log), EventAction.IMPORT_STAGE, delivery, EventOutcome.SUCCESS)
                .durationNanos(duration.toNanos())
                .message("managed dataframe import staging completed")
                .log();
    }

    @Override
    public void promotionCompleted(
            ImportDelivery delivery, CanonicalImportResult result, Duration duration) {
        event(LogEvents.debug(log), EventAction.IMPORT_PROMOTE, delivery, EventOutcome.SUCCESS)
                .durationNanos(duration.toNanos())
                .field(LogField.IOC_IMPORT_PROMOTION_OUTCOME, result.outcome().name())
                .field(LogField.IOC_IMPORT_ACCEPTED_ROWS, result.acceptedRows())
                .field(LogField.IOC_IMPORT_REJECTED_ROWS, result.rejectedRows())
                .field(LogField.IOC_IMPORT_PUBLIC_MUTATIONS, result.publicMutations())
                .field(LogField.IOC_IMPORT_AFFECTED_ARTIFACTS, artifacts(result.affectedArtifacts()))
                .message("managed dataframe import canonical promotion completed")
                .log();
    }

    @Override
    public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
        delivery.lastErrorCode().flatMap(ImportDiagnosticCodes::findById).ifPresent(this::emit);
        LogEvent event = event(
                LogEvents.warn(log), EventAction.IMPORT_RETRY, delivery, EventOutcome.FAILURE)
                .field(LogField.IOC_IMPORT_RETRY_DELAY_MS, retryDelayMillis(delivery));
        errorType.ifPresent(value -> event.field(LogField.ERROR_TYPE, value));
        event.message("managed dataframe import retry scheduled").log();
    }

    @Override
    public void deliveryCompleted(
            ImportDelivery delivery, PublishImportReportCommand report, Duration duration) {
        report.deliveryCodes().stream()
                .map(ImportDiagnosticCodes::findById)
                .flatMap(Optional::stream)
                .forEach(this::emit);
        EventOutcome outcome = report.outcome() == ImportTerminalOutcome.SUCCEEDED
                ? EventOutcome.SUCCESS : EventOutcome.FAILURE;
        LogEvent event = report.outcome() == ImportTerminalOutcome.SUCCEEDED
                ? LogEvents.info(log) : LogEvents.warn(log);
        LogEvent completed = event(event, EventAction.IMPORT_COMPLETE, delivery, outcome)
                .durationNanos(duration.toNanos())
                .field(LogField.IOC_IMPORT_OUTCOME, report.outcome().name())
                .field(LogField.IOC_IMPORT_DISPOSITION, disposition(report.outcome()))
                .field(LogField.IOC_IMPORT_ACCEPTED_ROWS, report.acceptedRows())
                .field(LogField.IOC_IMPORT_REJECTED_ROWS, report.rejectedRows())
                .field(LogField.IOC_IMPORT_PUBLIC_MUTATIONS, report.publicMutations())
                .field(LogField.IOC_IMPORT_AFFECTED_ARTIFACTS, artifacts(report.affectedArtifacts()));
        failureReason(report).ifPresent(reason ->
                completed.field(LogField.IOC_IMPORT_FAILURE_REASON, reason.value()));
        completed.message(completionMessage(report.outcome())).log();
    }

    private LogEvent event(
            LogEvent event, EventAction action, ImportDelivery delivery, EventOutcome outcome) {
        event.action(action)
                .outcome(outcome)
                .field(LogField.IOC_IMPORT_DELIVERY_ID, delivery.id().value())
                .field(LogField.IOC_IMPORT_SEQUENCE, delivery.sequence().value())
                .field(LogField.IOC_SOURCE_ID, delivery.sourceId().value())
                .field(LogField.IOC_IMPORT_STATE, delivery.state().name())
                .field(LogField.IOC_IMPORT_ATTEMPT_COUNT, delivery.attemptCount());
        delivery.contract().ifPresent(contract -> event
                .field(LogField.IOC_IMPORT_CONTRACT_ID, contract.id().value())
                .field(LogField.IOC_IMPORT_CONTRACT_VERSION, contract.version()));
        return event;
    }

    private long retryDelayMillis(ImportDelivery delivery) {
        return delivery.nextAttemptAt()
                .map(next -> Duration.between(clock.instant(), next))
                .filter(duration -> !duration.isNegative())
                .map(Duration::toMillis)
                .orElse(0L);
    }

    private String artifacts(Set<String> affectedArtifacts) {
        return affectedArtifacts.stream().sorted().reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private Optional<DelimitedInputReadException.Reason> failureReason(
            PublishImportReportCommand report) {
        return report.deliveryCodes().stream()
                .map(DelimitedInputReadException.Reason::fromReportCode)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private String disposition(ImportTerminalOutcome outcome) {
        return outcome == ImportTerminalOutcome.REJECTED ? "quarantine" : "terminal";
    }

    private String completionMessage(ImportTerminalOutcome outcome) {
        return outcome == ImportTerminalOutcome.REJECTED
                ? "managed dataframe import delivery quarantined"
                : "managed dataframe import delivery completed";
    }

    private void emit(ImportDiagnosticCodes code) {
        try {
            diagnostics.emit(diagnosticFactory.create(code).build());
        } catch (RuntimeException ignored) {
            // Diagnostic delivery remains observational and independent of logging.
        }
    }
}
