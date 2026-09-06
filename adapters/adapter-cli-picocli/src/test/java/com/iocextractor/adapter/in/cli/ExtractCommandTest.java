package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionCommand;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractCommandTest {

    @ParameterizedTest
    @MethodSource("completionOutcomes")
    void prints_correlated_completion_summary_and_returns_distinct_exit(
            CompletionStatus status,
            DiagnosticSummary summary,
            int expectedExit,
            String goldenResource) {
        var capturedCommand = new AtomicReference<ExtractionCommand>();
        ExtractIocsUseCase extractor = command -> {
            capturedCommand.set(command);
            return new ExtractionResult(command.runId(), 2, 1, Map.of(), status, List.of(), summary);
        };
        var beans = new StaticListableBeanFactory();
        beans.addBean("extractor", extractor);
        var command = new ExtractCommand(beans.getBeanProvider(ExtractIocsUseCase.class), "test");
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.out;

        int exit;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            exit = new CommandLine(command).execute("--source", "source.htm");
        } finally {
            System.setOut(previous);
        }

        String runId = capturedCommand.get().runId();
        assertThat(runId).isNotBlank();
        assertThat(exit).isEqualTo(expectedExit);
        assertThat(completionPayload(output.toString(StandardCharsets.UTF_8), runId)
                .replace(runId, "<RUN_ID>"))
                .isEqualTo(CliGolden.text(goldenResource));
    }

    private String completionPayload(String output, String runId) {
        String lineSeparator = System.lineSeparator();
        int start = output.indexOf("Run=" + runId + ", completion=");
        int retained = output.indexOf("Extracted=2, retained=1", start);
        assertThat(start).as("completion summary start").isGreaterThanOrEqualTo(0);
        assertThat(retained).as("completion summary end line").isGreaterThanOrEqualTo(start);
        int newline = output.indexOf(lineSeparator, retained);
        assertThat(newline).as("completion summary terminating newline").isGreaterThan(retained);
        int end = newline + lineSeparator.length();
        return output.substring(start, end);
    }

    private static Stream<Arguments> completionOutcomes() {
        return Stream.of(
                Arguments.of(CompletionStatus.COMPLETED, DiagnosticSummary.empty(), 0,
                        "extract-completed.txt"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_WARNINGS,
                        new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.WARN, 1L)),
                        0, "extract-warnings.txt"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_ERRORS,
                        new DiagnosticSummary(3, 1, Map.of(
                                DiagnosticSeverity.ERROR, 2L,
                                DiagnosticSeverity.WARN, 1L)),
                        3, "extract-errors.txt"));
    }
}
