package com.iocextractor.application.pipeline.stage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicateIndicatorsStageTest {

    @Test
    void removes_within_batch_duplicates_when_enabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var second = StageTestSupport.indicator("second.example");
        var stage = new DeduplicateIndicatorsStage(true);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.classifiedIndicators(first, duplicate, second), false));

        assertThat(output.payload().extracted()).extracting(item -> item.indicator())
                .containsExactly(first, duplicate, second);
        assertThat(output.payload().retained()).extracting(item -> item.indicator())
                .containsExactly(first, second);
    }

    @Test
    void keeps_all_indicators_when_deduplication_disabled() {
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var stage = new DeduplicateIndicatorsStage(false);

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.classifiedIndicators(first, duplicate), false));

        assertThat(output.payload().retained()).extracting(item -> item.indicator())
                .containsExactly(first, duplicate);
    }
}
