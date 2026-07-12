package com.iocextractor.application.pipeline.stage;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.MaskMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifyIndicatorsStageTest {

    @Test
    void materializes_exactly_one_decision_per_indicator() {
        var calls = new AtomicInteger();
        var stage = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            return new ClassificationDecision(
                    new IndicatorFeatures(indicator.value(), indicator.value(),
                            false, false, false, HostKind.REGISTRABLE),
                    1, List.of("is-registrable"), new MaskMatch("u:hAS", "h:dAS"));
        });
        var first = StageTestSupport.indicator("first.example");
        var second = StageTestSupport.indicator("second.example");

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.attributedIndicators(first, second), false));

        assertThat(calls).hasValue(2);
        assertThat(output.payload().indicators())
                .extracting(classified -> classified.classification().matchedPredicates())
                .containsExactly(List.of("is-registrable"), List.of("is-registrable"));
    }
}
