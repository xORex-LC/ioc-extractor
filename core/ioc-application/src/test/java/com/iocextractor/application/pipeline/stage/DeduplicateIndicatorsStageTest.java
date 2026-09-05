package com.iocextractor.application.pipeline.stage;

import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateIndicatorsStageTest {

    @Test
    void removes_within_batch_duplicates_when_enabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var second = StageTestSupport.indicator("second.example");
        var stage = new DeduplicateIndicatorsStage(
                true, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(first, duplicate, second), false));

        assertThat(output.payload().extracted()).isEqualTo(3);
        assertThat(output.payload().retained()).containsExactly(first, second);
        assertThat(output.payload().decisions())
                .extracting(decision -> decision.retained())
                .containsExactly(true, false, true);
        assertThat(output.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.ITEM_SKIPPED);
            assertThat(diagnostic.context()).containsEntry("item", duplicate.value());
        });
    }

    @Test
    void keeps_all_indicators_when_deduplication_disabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var stage = new DeduplicateIndicatorsStage(
                false, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(first, duplicate), false));

        assertThat(output.payload().extracted()).isEqualTo(2);
        assertThat(output.payload().retained()).containsExactly(first, duplicate);
        assertThat(output.diagnostics()).isEmpty();
    }

    @Test
    void keeps_first_provenance_while_type_remains_part_of_dedup_identity() {
        var first = indicator("same.test", IndicatorType.DOMAIN, "first-source");
        var duplicateWithLaterSource = indicator("same.test", IndicatorType.DOMAIN, "later-source");
        var sameValueDifferentType = indicator("same.test", IndicatorType.URL, "url-source");
        var stage = new DeduplicateIndicatorsStage(
                true, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(
                        first, duplicateWithLaterSource, sameValueDifferentType), false));

        assertThat(output.payload().retained())
                .containsExactly(first, sameValueDifferentType);
        assertThat(output.payload().retained().getFirst().source().label())
                .isEqualTo("first-source");
    }

    private static Indicator indicator(String value, IndicatorType type, String source) {
        return new Indicator(value, type, new SourceContext(source, null));
    }
}
