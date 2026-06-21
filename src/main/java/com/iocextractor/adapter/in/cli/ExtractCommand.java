package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.ObservabilityMode;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
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

    private static final Logger log = LoggerFactory.getLogger(ExtractCommand.class);

    private final ExtractIocsUseCase useCase;

    @Option(names = {"-s", "--source"}, required = true,
            description = "Path to the source document (.htm/.docx/.pdf/...).")
    private Path source;

    @Option(names = "--dry-run",
            description = "Extract and report, but do not write any artifact.")
    private boolean dryRun;

    public ExtractCommand(ExtractIocsUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Integer call() {
        LogEvents.info(log)
                .action(EventAction.COMMAND_START)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_MODE, ObservabilityMode.ONESHOT.value())
                .field(LogField.IOC_SOURCE_PATH, source)
                .message("command started")
                .log();
        try {
            ExtractionResult result = useCase.extract(new ExtractionCommand(source, dryRun));
            System.out.printf("Extracted=%d, retained=%d%n", result.extracted(), result.retained());
            result.writtenPerArtifact().forEach((artifact, rows) ->
                    System.out.printf("  %-8s -> %d rows%n", artifact, rows));
            LogEvents.info(log)
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(EventOutcome.SUCCESS)
                    .field(LogField.IOC_MODE, ObservabilityMode.ONESHOT.value())
                    .field(LogField.IOC_SOURCE_PATH, source)
                    .message("command completed")
                    .log();
            return 0;
        } catch (RuntimeException ex) {
            LogEvents.error(log)
                    .action(EventAction.COMMAND_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_MODE, ObservabilityMode.ONESHOT.value())
                    .field(LogField.IOC_SOURCE_PATH, source)
                    .message("command failed")
                    .log(ex);
            throw ex;
        }
    }
}
