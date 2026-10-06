package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;

/** Low-cardinality terminal observation; never an execution authority. */
final class DocumentCompletionObserver {
    private static final Logger log = LoggerFactory.getLogger(DocumentCompletionObserver.class);
    private DocumentCompletionObserver() { }
    static void completed(IngestSourceResult result, Path source, SourceKey key) {
        if (result.status() == com.iocextractor.application.ingest.IngestionStatus.FAILED) {
            LogEvents.warn(log).action(EventAction.SOURCE_INGEST).outcome(EventOutcome.FAILURE)
                    .field(LogField.FILE_PATH, source).field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                    .message("source rejected").log();
            return;
        }
        var extraction = result.extractionResultOptional();
        if (extraction.isEmpty()) {
            LogEvents.info(log)
                    .action(EventAction.SOURCE_INGEST)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.FILE_PATH, source)
                    .field(LogField.IOC_SOURCE_CONTENT_HASH, key.value())
                    .field(LogField.IOC_INGEST_DISPOSITION,
                            result.duplicate() ? "duplicate" : "handled_without_extraction")
                    .message(result.duplicate()
                            ? "source confirmation receipt replayed"
                            : "source handled without extraction")
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

    private static void addDiagnosticFields(LogEvent event, ExtractionResult result) {
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

    private static String completionMessage(CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> "source ingested";
            case COMPLETED_WITH_WARNINGS -> "source ingested with warnings";
            case COMPLETED_WITH_ERRORS -> "source ingested with errors";
        };
    }

}
