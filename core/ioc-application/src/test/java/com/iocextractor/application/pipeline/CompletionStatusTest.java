package com.iocextractor.application.pipeline;

import com.iocextractor.application.observability.PipelineDecisionKind;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.result.DiagnosticSummary;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void pipelineDecisionBuilderPreservesTypedOptionalEvidence() {
        PipelineItemDecision decision = PipelineItemDecision.builder(
                        PipelineDecisionKind.EXTRACTION, "accepted")
                .identity("item-1")
                .item("DOMAIN", "example.test")
                .rule("domain-rule")
                .pattern("domain-pattern")
                .result("DOMAIN")
                .span(2, 14)
                .artifact("masks")
                .build();

        assertThat(decision)
                .extracting(PipelineItemDecision::identity,
                        PipelineItemDecision::indicatorType,
                        PipelineItemDecision::spanStart,
                        PipelineItemDecision::spanEnd,
                        PipelineItemDecision::artifact)
                .containsExactly("item-1", "DOMAIN", 2, 14, "masks");
    }

    @Test
    void pipelineDecisionRejectsMissingOutcomeAndInvalidHalfOpenSpan() {
        assertThatThrownBy(() -> PipelineItemDecision.builder(null, "accepted"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("kind");
        assertThatThrownBy(() -> PipelineItemDecision.builder(
                PipelineDecisionKind.EXTRACTION, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
        assertThatThrownBy(() -> PipelineItemDecision.builder(
                        PipelineDecisionKind.EXTRACTION, "accepted")
                .span(-1, 1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spanStart");
        assertThatThrownBy(() -> PipelineItemDecision.builder(
                        PipelineDecisionKind.EXTRACTION, "accepted")
                .span(0, -1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spanEnd");
        assertThatThrownBy(() -> PipelineItemDecision.builder(
                        PipelineDecisionKind.EXTRACTION, "accepted")
                .span(2, 1)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede");
    }
}
