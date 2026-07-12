package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionStatusTest {

    @Test
    void distinguishes_clean_warning_and_error_completion() {
        assertThat(CompletionStatus.from(DiagnosticSummary.empty())).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(CompletionStatus.from(new DiagnosticSummary(1, 0,
                Map.of(DiagnosticSeverity.WARN, 1L))))
                .isEqualTo(CompletionStatus.COMPLETED_WITH_WARNINGS);
        assertThat(CompletionStatus.from(new DiagnosticSummary(2, 1,
                Map.of(DiagnosticSeverity.ERROR, 1L, DiagnosticSeverity.WARN, 1L))))
                .isEqualTo(CompletionStatus.COMPLETED_WITH_ERRORS);
    }
}
