package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.in.ingest.RejectIngestionUseCase;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticCategory;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.diagnostics.sink.DiagnosticSink;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Handles one file message from Spring Integration and delegates the business
 * operation to {@link IngestSourceUseCase}.
 */
public final class FileSourceMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(FileSourceMessageHandler.class);

    private final FileSourceHasher hasher;
    private final IngestSourceUseCase useCase;
    private final RejectIngestionUseCase rejectUseCase;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;
    private final DiagnosticSink diagnosticSink;

    public FileSourceMessageHandler(FileSourceHasher hasher,
                                    IngestSourceUseCase useCase,
                                    RejectIngestionUseCase rejectUseCase,
                                    Clock clock,
                                    int maxAttempts,
                                    Duration backoff,
                                    DiagnosticSink diagnosticSink) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.rejectUseCase = Objects.requireNonNull(rejectUseCase, "rejectUseCase");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Duration.ZERO : backoff;
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    public void handle(File file) {
        Path source = file.toPath();
        var key = hasher.sha256(source);
        RuntimeException last = null;
        boolean alreadyRejected = false;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                IngestSourceResult result = useCase.ingest(
                        new IngestSourceCommand(source, key, Instant.now(clock)));
                if (result.status() == IngestionStatus.FAILED) {
                    alreadyRejected = true;
                    if (last == null) {
                        last = new IocExtractorException(
                                "Source is already marked failed: " + source);
                    }
                    break;
                }
                logHandledSource(result, source, key);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleep();
                }
            }
        }
        RuntimeException terminal = last;
        if (!alreadyRejected) {
            try {
                rejectUseCase.reject(key, last == null ? "source ingestion failed" : last.getMessage());
            } catch (RuntimeException rejectionFailure) {
                if (last != null) {
                    rejectionFailure.addSuppressed(last);
                }
                terminal = rejectionFailure;
            }
        }
        emitIngestDiagnostic(terminal);
        throw new IocExtractorException("Source ingestion failed after retries: " + source, terminal);
    }

    private void emitIngestDiagnostic(RuntimeException failure) {
        if (failure instanceof DiagnosticException diagnosticFailure
                && diagnosticFailure.diagnostic().category() == DiagnosticCategory.INGEST) {
            diagnosticSink.emit(diagnosticFailure.diagnostic());
        }
    }

    private void logHandledSource(IngestSourceResult result, Path source, SourceKey key) {
        var extraction = result.extractionResultOptional();
        if (extraction.isEmpty()) {
            LogEvents.info(log)
                    .action(EventAction.SOURCE_INGEST)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.FILE_PATH, source)
                    .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                    .message(result.duplicate() ? "source duplicate skipped" : "source handled without extraction")
                    .log();
            return;
        }

        ExtractionResult completed = extraction.orElseThrow();
        CompletionStatus status = completed.completionStatus();
        LogEvent event = status == CompletionStatus.COMPLETED ? LogEvents.info(log) : LogEvents.warn(log);
        event.action(EventAction.SOURCE_INGEST)
                .outcome(status == CompletionStatus.COMPLETED_WITH_ERRORS
                        ? EventOutcome.FAILURE : EventOutcome.SUCCESS)
                .field(LogField.IOC_RUN_ID, completed.runId())
                .field(LogField.FILE_PATH, source)
                .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value());
        addDiagnosticFields(event, completed);
        event.message(completionMessage(status)).log();
    }

    private void addDiagnosticFields(LogEvent event, ExtractionResult result) {
        DiagnosticSummary summary = result.diagnosticSummary();
        event.field(LogField.IOC_COMPLETION_STATUS, result.completionStatus())
                .field(LogField.IOC_DIAGNOSTIC_TOTAL, summary.total())
                .field(LogField.IOC_DIAGNOSTIC_SUPPRESSED, summary.suppressed())
                .field(LogField.IOC_DIAGNOSTIC_FATAL_COUNT, summary.count(DiagnosticSeverity.FATAL))
                .field(LogField.IOC_DIAGNOSTIC_ERROR_COUNT, summary.count(DiagnosticSeverity.ERROR))
                .field(LogField.IOC_DIAGNOSTIC_WARN_COUNT, summary.count(DiagnosticSeverity.WARN))
                .field(LogField.IOC_DIAGNOSTIC_INFO_COUNT, summary.count(DiagnosticSeverity.INFO))
                .field(LogField.IOC_DIAGNOSTIC_DEBUG_COUNT, summary.count(DiagnosticSeverity.DEBUG))
                .field(LogField.IOC_DIAGNOSTIC_TRACE_COUNT, summary.count(DiagnosticSeverity.TRACE));
    }

    private String completionMessage(CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> "source ingested";
            case COMPLETED_WITH_WARNINGS -> "source ingested with warnings";
            case COMPLETED_WITH_ERRORS -> "source ingested with errors";
        };
    }

    private void sleep() {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Interrupted while waiting for ingest retry", e);
        }
    }
}
