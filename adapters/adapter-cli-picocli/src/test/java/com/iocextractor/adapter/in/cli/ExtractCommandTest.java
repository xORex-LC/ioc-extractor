package com.iocextractor.adapter.in.cli;

import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.port.in.ExtractIocsUseCase;
import com.iocextractor.application.port.in.ExtractionResult;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractCommandTest {

    @Test
    void returns_distinct_non_zero_exit_for_structurally_completed_run_with_errors() {
        ExtractIocsUseCase extractor = ignored -> new ExtractionResult(
                2, 1, Map.of(), CompletionStatus.COMPLETED_WITH_ERRORS,
                List.of(), new DiagnosticSummary(1, 0, Map.of(DiagnosticSeverity.ERROR, 1L)));
        var beans = new StaticListableBeanFactory();
        beans.addBean("extractor", extractor);
        var command = new ExtractCommand(beans.getBeanProvider(ExtractIocsUseCase.class), "test");

        int exit = new CommandLine(command).execute("--source", "source.htm");

        assertThat(exit).isEqualTo(3);
    }
}
