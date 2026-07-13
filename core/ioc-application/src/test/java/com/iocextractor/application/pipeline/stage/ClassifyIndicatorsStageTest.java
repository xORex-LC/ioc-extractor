package com.iocextractor.application.pipeline.stage;

import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.application.pipeline.payload.DeduplicationDecision;
import com.iocextractor.application.pipeline.payload.DeduplicatedIndicators;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifyIndicatorsStageTest {

    @Test
    void classifies_only_indicators_retained_by_deduplication() {
        var calls = new AtomicInteger();
        var classifier = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            return decision(indicator);
        }, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);
        var first = StageTestSupport.indicator("first.example");
        var duplicate = StageTestSupport.indicator("first.example");
        var deduplicated = new DeduplicateIndicatorsStage(
                true, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER).process(
                StageTestSupport.envelope(StageTestSupport.attributedIndicators(first, duplicate), false));

        var output = classifier.process(deduplicated);

        assertThat(calls).hasValue(1);
        assertThat(output.payload().extracted()).isEqualTo(2);
        assertThat(output.payload().retained())
                .extracting(classified -> classified.indicator())
                .containsExactly(first);
    }

    @Test
    void materializes_exactly_one_decision_per_retained_network_indicator() {
        var calls = new AtomicInteger();
        var stage = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            return decision(indicator);
        }, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);
        var first = StageTestSupport.indicator("first.example");
        var second = StageTestSupport.indicator("second.example");

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.deduplicatedIndicators(first, second), false));

        assertThat(calls).hasValue(2);
        assertThat(output.payload().extracted()).isEqualTo(2);
        assertThat(output.payload().retained())
                .extracting(classified -> classified.classification().matchedPredicates())
                .containsExactly(List.of("is-registrable"), List.of("is-registrable"));
    }

    @Test
    void retains_file_indicators_without_running_network_classification() {
        var calls = new AtomicInteger();
        var stage = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            throw new AssertionError("FILE indicator must not reach MatchPolicy");
        }, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);
        var hash = new Indicator("0123456789ABCDEF0123456789ABCDEF", IndicatorType.MD5,
                new SourceContext("test-source", null));

        var output = stage.process(StageTestSupport.envelope(
                StageTestSupport.deduplicatedIndicators(hash), false));

        assertThat(calls).hasValue(0);
        assertThat(output.payload().retained()).singleElement().satisfies(classified -> {
            assertThat(classified.indicator()).isEqualTo(hash);
            assertThat(classified.classification().matchedRuleIndex()).isEqualTo(-1);
            assertThat(classified.classification().matchedPredicates()).isEmpty();
            assertThat(classified.classification().features().hostKind()).isEqualTo(HostKind.UNKNOWN);
            assertThat(classified.classification().match())
                    .isEqualTo(new MaskMatch(null, null));
        });
    }

    @Test
    void tracesMaterializedDecisionWithoutRepeatingClassification() {
        var calls = new AtomicInteger();
        var tracer = new RecordingTracer();
        var stage = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            return decision(indicator);
        }, StageTestSupport.DIAGNOSTICS, tracer);

        stage.process(StageTestSupport.envelope(
                StageTestSupport.deduplicatedIndicators(StageTestSupport.indicator("example.com")), false));

        assertThat(calls).hasValue(1);
        assertThat(tracer.decisions).singleElement().satisfies(decision -> {
            assertThat(decision.kind().name()).isEqualTo("CLASSIFICATION");
            assertThat(decision.outcome()).isEqualTo("matched");
            assertThat(decision.rule()).isEqualTo("1");
            assertThat(decision.pattern()).isEqualTo("is-registrable");
        });
    }

    @Test
    void syntheticLargeBatchStillClassifiesOncePerItemWithTracingEnabled() {
        int batchSize = 10_000;
        var calls = new AtomicInteger();
        var tracer = new CountingTracer();
        var stage = new ClassifyIndicatorsStage(indicator -> {
            calls.incrementAndGet();
            return decision(indicator);
        }, StageTestSupport.DIAGNOSTICS, tracer);
        var indicators = new java.util.ArrayList<Indicator>(batchSize);
        var decisions = new java.util.ArrayList<DeduplicationDecision>(batchSize);
        for (int index = 0; index < batchSize; index++) {
            var indicator = StageTestSupport.indicator("item-" + index + ".example");
            indicators.add(indicator);
            decisions.add(new DeduplicationDecision(indicator, true));
        }

        stage.process(StageTestSupport.envelope(
                new DeduplicatedIndicators(batchSize, indicators, decisions), false));

        assertThat(calls).hasValue(batchSize);
        assertThat(tracer.traces).hasValue(batchSize);
    }

    private static ClassificationDecision decision(Indicator indicator) {
        return new ClassificationDecision(
                new IndicatorFeatures(indicator.value(), indicator.value(),
                        false, false, false, HostKind.REGISTRABLE),
                1, List.of("is-registrable"), new MaskMatch("u:hAS", "h:dAS"));
    }

    private static final class RecordingTracer implements PipelineDecisionTracer {

        private final java.util.ArrayList<PipelineItemDecision> decisions = new java.util.ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void trace(PipelineItemDecision decision) {
            decisions.add(decision);
        }
    }

    private static final class CountingTracer implements PipelineDecisionTracer {

        private final AtomicInteger traces = new AtomicInteger();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void trace(PipelineItemDecision decision) {
            traces.incrementAndGet();
        }
    }
}
