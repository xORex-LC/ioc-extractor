package com.iocextractor.application.pipeline.stage;

import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
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
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractIndicatorsStageTest {

    @Test
    void extracts_raw_indicators_and_keeps_text_for_next_stage() {
        var raw = new RawIndicator("example.com", IndicatorType.DOMAIN, 0);
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(raw), List.of()),
                StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

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
                text -> new ExtractionOutcome(List.of(), List.of(dropped)),
                StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

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
                StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER);

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

    @Test
    void emitsMachineStatusIndependentlyOfDefaultLocale() {
        var decision = new ExtractionDecision(
                IndicatorType.DOMAIN, "domain-pattern", new Span(0, 11, "example.com"),
                ExtractionDecisionStatus.DROPPED_OVERLAP);
        var tracer = new RecordingTracer();
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(), List.of(decision)),
                StageTestSupport.DIAGNOSTICS, tracer);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            stage.process(StageTestSupport.envelope(
                    new RefangedText(new RefangOutcome("example.com", List.of())), false));
        } finally {
            Locale.setDefault(previous);
        }

        assertThat(tracer.decisions).singleElement()
                .extracting(PipelineItemDecision::outcome)
                .isEqualTo("dropped_overlap");
    }

    @Test
    void bounds_overlap_details_during_construction_and_keeps_exact_pipeline_totals() {
        var dropped = new ExtractionDecision(IndicatorType.DOMAIN, "domain-pattern",
                new Span(8, 19, "example.com"), ExtractionDecisionStatus.DROPPED_OVERLAP);
        var stage = new ExtractIndicatorsStage(
                text -> new ExtractionOutcome(List.of(), java.util.Collections.nCopies(100_000, dropped)),
                StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER, 3);
        var input = StageTestSupport.envelope(
                new RefangedText(new RefangOutcome("https://example.com", List.of())), false);

        var output = stage.process(input);

        assertThat(output.diagnostics()).hasSize(3);
        assertThat(output.diagnosticSummary().total()).isEqualTo(100_000);
        assertThat(output.diagnosticSummary().suppressed()).isEqualTo(99_997);
        var sink = new com.iocextractor.diagnostics.sink.CollectingDiagnosticSink();
        var runner = new com.iocextractor.platform.etl.PipelineRunner(
                com.iocextractor.diagnostics.result.FailurePolicy.failFast(),
                new com.iocextractor.platform.etl.NoopPipelineObserver(), sink, StageTestSupport.DIAGNOSTICS, 3);
        var result = runner.runWithOutcome(input,
                com.iocextractor.platform.etl.Pipeline.<RefangedText>start().then(stage));
        assertThat(result.diagnosticSummary()).isEqualTo(output.diagnosticSummary());
        assertThat(sink.diagnostics()).hasSize(4).containsExactlyElementsOf(result.envelope().diagnostics());
        assertThat(sink.diagnostics().getLast().context()).containsEntry("suppressedCount", 99_997L);
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void concurrent_invocations_of_the_same_stage_keep_independent_diagnostic_budgets() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(2);
        var dropped = new ExtractionDecision(IndicatorType.DOMAIN, "domain-pattern",
                new Span(0, 11, "example.com"), ExtractionDecisionStatus.DROPPED_OVERLAP);
        var stage = new ExtractIndicatorsStage(text -> {
            entered.countDown();
            try {
                if (!entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent stages did not rendezvous");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return new ExtractionOutcome(List.of(), java.util.Collections.nCopies(
                    text.equals("one") ? 50_000 : 100_000, dropped));
        }, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER, 3);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> stage.process(StageTestSupport.envelope(
                    new RefangedText(new RefangOutcome("one", List.of())), false)));
            var second = workers.submit(() -> stage.process(StageTestSupport.envelope(
                    new RefangedText(new RefangOutcome("two", List.of())), false)));
            var one = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var two = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(one.diagnostics()).hasSize(3);
            assertThat(two.diagnostics()).hasSize(3);
            assertThat(one.diagnosticSummary().total()).isEqualTo(50_000);
            assertThat(two.diagnosticSummary().total()).isEqualTo(100_000);
            assertThat(one.diagnosticSummary().suppressed()).isEqualTo(49_997);
            assertThat(two.diagnosticSummary().suppressed()).isEqualTo(99_997);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class RecordingTracer implements PipelineDecisionTracer {
        private final List<PipelineItemDecision> decisions = new java.util.ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void trace(PipelineItemDecision decision) {
            decisions.add(decision);
        }
    }
}
