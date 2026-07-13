package com.iocextractor.application.pipeline.stage;

import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateIndicatorsStageTest {

    @Test
    void removes_within_batch_duplicates_when_enabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var second = StageTestSupport.indicator("second.example");
        var stage = new DeduplicateIndicatorsStage(true, StageTestSupport.DIAGNOSTICS);

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
        var stage = new DeduplicateIndicatorsStage(false, StageTestSupport.DIAGNOSTICS);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(first, duplicate), false));

        assertThat(output.payload().extracted()).isEqualTo(2);
        assertThat(output.payload().retained()).containsExactly(first, duplicate);
        assertThat(output.diagnostics()).isEmpty();
    }
}
