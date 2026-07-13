package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.diagnostics.codes.ExtractionDiagnosticCodes;
import com.iocextractor.domain.extract.ExtractionDecision;
import com.iocextractor.domain.extract.ExtractionDecisionStatus;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.extract.Span;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractIndicatorsStageTest {

    @Test
    void extracts_raw_indicators_and_keeps_text_for_next_stage() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(raw), List.of()), StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.envelope(
                new RefangedText(new RefangOutcome("example.com", List.of())), false));

        assertThat(output.payload().text()).isEqualTo("example.com");
        assertThat(output.payload().rawIndicators()).containsExactly(raw);
    }

    @Test
    void attaches_one_debug_diagnostic_for_each_overlap_drop() {
        var dropped = new ExtractionDecision(
                IndicatorType.DOMAIN, "domain-pattern", new Span(8, 19, "example.com"),
                ExtractionDecisionStatus.DROPPED_OVERLAP);
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(), List.of(dropped)), StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.envelope(
                new RefangedText(new RefangOutcome("https://example.com", List.of())), false));

        assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(ExtractionDiagnosticCodes.INDICATOR_SKIPPED);
            assertThat(diagnostic.context())
                    .containsEntry("indicator", "example.com")
                    .containsEntry("type", IndicatorType.DOMAIN)
                    .containsEntry("spanStart", 8)
                    .containsEntry("spanEnd", 19);
        });
    }

    @Test
    void distinguishes_exact_cross_type_overlap_as_ambiguous_value() {
        var accepted = new ExtractionDecision(
                IndicatorType.URL, "url-pattern", new Span(0, 19, "https://example.com"),
                ExtractionDecisionStatus.ACCEPTED);
        var ambiguous = new ExtractionDecision(
                IndicatorType.DOMAIN, "domain-pattern", new Span(0, 19, "https://example.com"),
                ExtractionDecisionStatus.DROPPED_OVERLAP);
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(), List.of(accepted, ambiguous)),
                StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.envelope(
                new RefangedText(new RefangOutcome("https://example.com", List.of())), false));

        assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(ExtractionDiagnosticCodes.AMBIGUOUS_VALUE);
            assertThat(diagnostic.context())
                    .containsEntry("value", "https://example.com")
                    .containsEntry("type", IndicatorType.DOMAIN)
                    .containsEntry("reason", "also matched higher-priority type URL");
        });
    }
}
