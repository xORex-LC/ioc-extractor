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
            String expectedSeverityLine) {
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
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Run=" + runId + ", completion=" + status)
                .contains("Diagnostics: total=" + summary.total()
                        + ", suppressed=" + summary.suppressed())
                .contains(expectedSeverityLine)
                .contains("Extracted=2, retained=1");
    }

    private static Stream<Arguments> completionOutcomes() {
        return Stream.of(
                Arguments.of(CompletionStatus.COMPLETED, DiagnosticSummary.empty(), 0,
                        "Diagnostics: total=0, suppressed=0"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_WARNINGS,
                        new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.WARN, 1L)),
                        0, "WARN=1"),
                Arguments.of(CompletionStatus.COMPLETED_WITH_ERRORS,
                        new DiagnosticSummary(3, 1, Map.of(
                                DiagnosticSeverity.ERROR, 2L,
                                DiagnosticSeverity.WARN, 1L)),
                        3, "ERROR=2"));
    }
}
