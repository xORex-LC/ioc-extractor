package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
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
        ExtractionResult result = useCase.extract(new ExtractionCommand(source, dryRun));
        System.out.printf("Extracted=%d, retained=%d%n", result.extracted(), result.retained());
        result.writtenPerArtifact().forEach((artifact, rows) ->
                System.out.printf("  %-8s -> %d rows%n", artifact, rows));
        return 0;
    }
}
