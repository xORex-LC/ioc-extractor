package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-21T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void runs_stages_in_pipeline_order_and_sets_stage_metadata() {
        var seenStages = new ArrayList<StageId>();
        var pipeline = Pipeline.<String>start()
                .then(new RecordingStage(new StageId("READ_SOURCE"), "-read", seenStages))
                .then(new RecordingStage(new StageId("REFANG"), "-refang", seenStages));

        var output = new PipelineRunner(FailurePolicy.failFast())
                .run(Envelope.of("start", meta()), pipeline);

        assertThat(output.payload()).isEqualTo("start-read-refang");
        assertThat(seenStages).containsExactly(new StageId("READ_SOURCE"), new StageId("REFANG"));
        assertThat(output.meta().stage()).isEqualTo(new StageId("REFANG"));
    }

    @Test
    void stops_when_failure_policy_rejects_accumulated_diagnostics() {
        var diagnostic = diagnostic(DiagnosticSeverity.ERROR);
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic));

        assertThatThrownBy(() -> runner(FailurePolicy.failFast(), diagnostics)
                .run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic")
                .isEqualTo(diagnostic);
        assertThat(diagnostics.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void collect_and_continue_allows_error_diagnostics() {
        var diagnostic = diagnostic(DiagnosticSeverity.ERROR);
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic))
                .then(new RecordingStage(new StageId("REFANG"), "-next", new ArrayList<>()));

        var output = new PipelineRunner(FailurePolicy.collectAndContinue())
                .run(Envelope.of("start", meta()), pipeline);

        assertThat(output.payload()).isEqualTo("start-next");
        assertThat(output.diagnostics()).contains(diagnostic);
    }

    @Test
    void diagnostic_budget_retains_first_error_and_emits_one_summary() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var error = diagnostic(DiagnosticSeverity.ERROR);
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(warning))
                .then(new DiagnosticStage(error));
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(),
                new NoopPipelineObserver(), diagnostics, new DiagnosticFactory(CLOCK), 1);

        var result = runner.runWithOutcome(Envelope.of("start", meta()), pipeline);
        var output = result.envelope();

        assertThat(output.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(PipelineDiagnosticCodes.STAGE_FAILED,
                        PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED);
        assertThat(output.diagnostics().getFirst().severity()).isEqualTo(DiagnosticSeverity.ERROR);
        assertThat(diagnostics.diagnostics()).containsExactly(warning, error, output.diagnostics().get(1));
        assertThat(result.diagnosticSummary().total()).isEqualTo(2);
        assertThat(result.diagnosticSummary().suppressed()).isOne();
        assertThat(result.diagnosticSummary().hasErrors()).isTrue();
    }

    @Test
    void failFastStillEmitsSuppressionSummaryBeforeStopping() {
        var warning = diagnostic(DiagnosticSeverity.WARN);
        var error = diagnostic(DiagnosticSeverity.ERROR);
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(warning))
                .then(new DiagnosticStage(error));
        var runner = new PipelineRunner(FailurePolicy.failFast(),
                new NoopPipelineObserver(), diagnostics, new DiagnosticFactory(CLOCK), 1);

        assertThatThrownBy(() -> runner.run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic")
                .isEqualTo(error);

        assertThat(diagnostics.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly(
                        PipelineDiagnosticCodes.STAGE_FAILED,
                        PipelineDiagnosticCodes.STAGE_FAILED,
                        PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED);
        assertThat(diagnostics.diagnostics().getLast().context())
                .containsEntry("suppressedCount", 1L);
    }

    @Test
    void opens_and_closes_observer_scope_around_stage_execution() {
        var seen = new ArrayList<String>();
        var events = new ArrayList<String>();
        var pipeline = Pipeline.<String>start()
                .then(new ObserverRecordingStage(seen));

        new PipelineRunner(FailurePolicy.failFast(), new RecordingObserver(events))
                .run(Envelope.of("start", meta().withAttribute("mode", "daemon")), pipeline);

        assertThat(seen).containsExactly("READ_SOURCE");
        assertThat(events).containsExactly(
                "open-run:run-1",
                "open-stage:READ_SOURCE",
                "started:READ_SOURCE",
                "completed:READ_SOURCE",
                "close-stage",
                "close-run");
    }

    @Test
    void emitsSuppressionSummaryOnceWhenALaterStageThrows() {
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new ThrowingStage(new IllegalStateException("boom")));
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(),
                new NoopPipelineObserver(), diagnostics, new DiagnosticFactory(CLOCK), 1);

        assertThatThrownBy(() -> runner.run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .hasRootCauseMessage("boom");

        assertThat(diagnostics.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED)
                .singleElement()
                .satisfies(summary -> assertThat(summary.context()).containsEntry("suppressedCount", 1L));
    }

    @Test
    void emitsSuppressionSummaryAfterStageScopeAndBeforeRunScopeClose() {
        var events = new ArrayList<String>();
        var observer = new RecordingObserver(events);
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)));
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), observer, diagnostic -> {
            if (diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED) {
                events.add("summary");
            }
        }, new DiagnosticFactory(CLOCK), 1);

        runner.run(Envelope.of("start", meta()), pipeline);

        assertThat(events).containsSubsequence("close-stage", "summary", "close-run");
        assertThat(events).containsOnlyOnce("summary");
    }

    @Test
    void emits_typed_stage_exception_once_and_preserves_it() {
        var diagnostic = diagnostic(DiagnosticSeverity.FATAL);
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start().then(new ThrowingStage(new DiagnosticException(diagnostic)));

        assertThatThrownBy(() -> runner(FailurePolicy.collectAndContinue(), diagnostics)
                .run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic")
                .isEqualTo(diagnostic);
        assertThat(diagnostics.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    void converts_generic_stage_exception_to_typed_diagnostic() {
        var failure = new IllegalStateException("boom");
        var diagnostics = new CollectingDiagnosticSink();
        var pipeline = Pipeline.<String>start().then(new ThrowingStage(failure));

        assertThatThrownBy(() -> runner(FailurePolicy.collectAndContinue(), diagnostics)
                .run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .hasCause(failure);
        assertThat(diagnostics.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(PipelineDiagnosticCodes.STAGE_FAILED);
            assertThat(diagnostic.context())
                    .containsEntry("stage", "EXTRACT")
                    .containsEntry("reason", "boom");
            assertThat(diagnostic.cause()).contains(failure);
        });
    }

    @Test
    void throwingSinkOnTerminalSummaryDoesNotMaskTheStageFailure() {
        var sinkFailure = new IllegalStateException("summary delivery down");
        var stageFailure = new DiagnosticException(diagnostic(DiagnosticSeverity.FATAL));
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new ThrowingStage(stageFailure));
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), new NoopPipelineObserver(),
                diagnostic -> {
                    if (diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED) {
                        throw sinkFailure;
                    }
                }, new DiagnosticFactory(CLOCK), 1);

        assertThatThrownBy(() -> runner.run(Envelope.of("start", meta()), pipeline))
                .isSameAs(stageFailure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).contains(sinkFailure));
    }

    @Test
    void throwingSinkOnTerminalSummarySurfacesOnCleanCompletion() {
        var sinkFailure = new IllegalStateException("summary delivery down");
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)))
                .then(new DiagnosticStage(diagnostic(DiagnosticSeverity.WARN)));
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), new NoopPipelineObserver(),
                diagnostic -> {
                    if (diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED) {
                        throw sinkFailure;
                    }
                }, new DiagnosticFactory(CLOCK), 1);

        assertThatThrownBy(() -> runner.run(Envelope.of("start", meta()), pipeline))
                .isSameAs(sinkFailure);
    }

    @Test
    void preserves_stage_failure_when_failure_observer_also_fails() {
        var stageFailure = new DiagnosticException(diagnostic(DiagnosticSeverity.FATAL));
        var observationFailure = new IllegalStateException("stage observer failed");
        var pipeline = Pipeline.<String>start().then(new ThrowingStage(stageFailure));
        var runner = new PipelineRunner(
                FailurePolicy.collectAndContinue(),
                new FailureThrowingObserver(observationFailure));

        assertThatThrownBy(() -> runner.run(Envelope.of("start", meta()), pipeline))
                .isSameAs(stageFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(observationFailure));
    }

    private PipelineRunner runner(FailurePolicy policy, CollectingDiagnosticSink diagnostics) {
        return new PipelineRunner(policy, new NoopPipelineObserver(), diagnostics, new DiagnosticFactory(CLOCK));
    }

    private EnvelopeMeta meta() {
        return EnvelopeMeta.initial("run-1", "source.html", CLOCK);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "test")
                .with("reason", "failed")
                .build();
    }

    private record RecordingStage(StageId name, String suffix, List<StageId> seenStages)
            implements Stage<String, String> {

        @Override
        public Envelope<String> process(Envelope<String> input) {
            seenStages.add(input.meta().stage());
            return input.withPayload(input.payload() + suffix);
        }
    }

    private record DiagnosticStage(Diagnostic diagnostic) implements Stage<String, String> {

        @Override
        public StageId name() {
            return new StageId("EXTRACT");
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            return input.withDiagnostic(diagnostic);
        }
    }

    private record ThrowingStage(RuntimeException failure) implements Stage<String, String> {

        @Override
        public StageId name() {
            return new StageId("EXTRACT");
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            throw failure;
        }
    }

    private record ObserverRecordingStage(List<String> seen) implements Stage<String, String> {

        @Override
        public StageId name() {
            return new StageId("READ_SOURCE");
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            seen.add(input.meta().stage().value());
            return input;
        }
    }

    private record RecordingObserver(List<String> events) implements PipelineObserver {

        @Override
        public AutoCloseable openRun(EnvelopeMeta meta) {
            events.add("open-run:" + meta.runId());
            return () -> events.add("close-run");
        }

        @Override
        public AutoCloseable openStage(EnvelopeMeta meta) {
            events.add("open-stage:" + meta.stage().value());
            return () -> events.add("close-stage");
        }

        @Override
        public void stageStarted(EnvelopeMeta meta) {
            events.add("started:" + meta.stage().value());
        }

        @Override
        public void stageCompleted(EnvelopeMeta meta, long durationNanos) {
            events.add("completed:" + meta.stage().value());
        }

        @Override
        public void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException failure) {
            events.add("failed:" + meta.stage().value());
        }
    }

    private record FailureThrowingObserver(RuntimeException failure) implements PipelineObserver {

        @Override
        public AutoCloseable openStage(EnvelopeMeta meta) {
            return () -> {
            };
        }

        @Override
        public void stageStarted(EnvelopeMeta meta) {
        }

        @Override
        public void stageCompleted(EnvelopeMeta meta, long durationNanos) {
        }

        @Override
        public void stageFailed(EnvelopeMeta meta, long durationNanos, RuntimeException stageFailure) {
            throw failure;
        }
    }
}
