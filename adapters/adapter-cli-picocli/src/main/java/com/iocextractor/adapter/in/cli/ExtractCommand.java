package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvent;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Inbound (driving) adapter: the {@code extract} CLI command. Translates CLI
 * arguments into an {@link ExtractionCommand} and invokes the use-case port.
 */
@Component
@Command(
        name = "extract",
        mixinStandardHelpOptions = true,
        description = "Extract, refang and normalize IOCs from a source document into reputation artifacts.")
public final class ExtractCommand implements Callable<Integer> {

    private static final int COMPLETED_WITH_ERRORS_EXIT_CODE = 3;
    private static final List<DiagnosticSeverity> DISPLAY_SEVERITIES = List.of(
            DiagnosticSeverity.FATAL,
            DiagnosticSeverity.ERROR,
            DiagnosticSeverity.WARN,
            DiagnosticSeverity.INFO,
            DiagnosticSeverity.DEBUG,
            DiagnosticSeverity.TRACE);
    private static final Logger log = LoggerFactory.getLogger(ExtractCommand.class);

    private final ObjectProvider<ExtractIocsUseCase> useCase;
    private final String observabilityMode;

    @Option(names = {"-s", "--source"}, required = true,
            description = "Path to the source document (.htm/.docx/.pdf/...).")
    private Path source;

    @Option(names = "--dry-run",
            description = "Extract and report, but do not write any artifact.")
    private boolean dryRun;

    public ExtractCommand(ObjectProvider<ExtractIocsUseCase> useCase,
                          @Value("${ioc.observability.mode:oneshot}") String observabilityMode) {
        this.useCase = useCase;
        this.observabilityMode = observabilityMode;
    }

    @Override
    public Integer call() {
        String runId = UUID.randomUUID().toString();
        LogEvents.info(log)
                .action(EventAction.COMMAND_START)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_RUN_ID, runId)
                .field(LogField.IOC_MODE, observabilityMode)
                .field(LogField.IOC_SOURCE_PATH, source)
                .message("command started")
                .log();
        try {
            ExtractIocsUseCase extractor = useCase.getIfAvailable();
            if (extractor == null) {
                throw new IllegalStateException("The 'extract' command is not available in daemon mode "
                        + "(ioc.runtime.mode=daemon); run with the default oneshot mode.");
            }
            ExtractionResult result = extractor.extract(new ExtractionCommand(runId, source, dryRun));
            printResult(result);
            boolean completedWithErrors = result.completionStatus() == CompletionStatus.COMPLETED_WITH_ERRORS;
            LogEvent completionEvent = completionEvent(result.completionStatus())
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(completedWithErrors ? EventOutcome.FAILURE : EventOutcome.SUCCESS)
                    .field(LogField.IOC_RUN_ID, result.runId())
                    .field(LogField.IOC_MODE, observabilityMode)
                    .field(LogField.IOC_SOURCE_PATH, source);
            addDiagnosticFields(completionEvent, result);
            completionEvent.message(completionMessage(result.completionStatus())).log();
            return completedWithErrors ? COMPLETED_WITH_ERRORS_EXIT_CODE : 0;
        } catch (RuntimeException ex) {
            LogEvents.error(log)
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_RUN_ID, runId)
                    .field(LogField.IOC_MODE, observabilityMode)
                    .field(LogField.IOC_SOURCE_PATH, source)
                    .message("command failed")
                    .log(ex);
            throw ex;
        }
    }

    private void printResult(ExtractionResult result) {
        DiagnosticSummary diagnostics = result.diagnosticSummary();
        System.out.printf("Run=%s, completion=%s%n", result.runId(), result.completionStatus());
        System.out.printf("Diagnostics: total=%d, suppressed=%d%n",
                diagnostics.total(), diagnostics.suppressed());
        DISPLAY_SEVERITIES.forEach(severity -> printSeverity(diagnostics, severity));
        System.out.printf("Extracted=%d, retained=%d%n", result.extracted(), result.retained());
        result.writtenPerArtifact().forEach((artifact, rows) ->
                System.out.printf("  %-8s -> %d rows%n", artifact, rows));
    }

    private void printSeverity(DiagnosticSummary summary, DiagnosticSeverity severity) {
        long count = summary.count(severity);
        if (count > 0) {
            System.out.printf("  %s=%d%n", severity, count);
        }
    }

    private LogEvent completionEvent(CompletionStatus status) {
        return status == CompletionStatus.COMPLETED ? LogEvents.info(log) : LogEvents.warn(log);
    }

    private String completionMessage(CompletionStatus status) {
        return switch (status) {
            case COMPLETED -> "command completed";
            case COMPLETED_WITH_WARNINGS -> "command completed with warnings";
            case COMPLETED_WITH_ERRORS -> "command completed with errors";
        };
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
}
