package com.iocextractor.observability.diagnostics;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LoggingPipelineObserver;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineRunner;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.platform.etl.StageId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PipelineDiagnosticMdcTest {

    private static final long WAIT_TIMEOUT_SECONDS = 5;

    @Test
    void emits_diagnostic_inside_the_stage_mdc_scope() {
        var observedMdc = new LinkedHashMap<String, String>();
        var clock = Clock.systemUTC();
        var runner = new PipelineRunner(
                FailurePolicy.collectAndContinue(),
                new LoggingPipelineObserver(),
                ignored -> observedMdc.putAll(MDC.getCopyOfContextMap()),
                new DiagnosticFactory(clock));
        var pipeline = Pipeline.<String>start().then(new DiagnosticStage(clock));

        runner.run(Envelope.of("input", EnvelopeMeta.initial("run-17", "source-17", clock)), pipeline);

        assertThat(observedMdc)
                .containsEntry(LogField.IOC_RUN_ID.key(), "run-17")
                .containsEntry(LogField.IOC_SOURCE_ID.key(), "source-17")
                .containsEntry(LogField.IOC_STAGE.key(), "EXTRACT");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void emitsSuppressionSummaryInsideRunScopeButOutsideStageScope() {
        var observedMdc = new LinkedHashMap<String, String>();
        var clock = Clock.systemUTC();
        var runner = new PipelineRunner(
                FailurePolicy.collectAndContinue(),
                new LoggingPipelineObserver(),
                diagnostic -> {
                    if (diagnostic.code() == PipelineDiagnosticCodes.DIAGNOSTICS_SUPPRESSED) {
                        observedMdc.putAll(MDC.getCopyOfContextMap());
                    }
                },
                new DiagnosticFactory(clock),
                1);
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(clock))
                .then(new DiagnosticStage(clock));

        runner.run(Envelope.of("input", EnvelopeMeta.initial("run-17", "source-17", clock)), pipeline);

        assertThat(observedMdc)
                .containsEntry(LogField.IOC_RUN_ID.key(), "run-17")
                .containsEntry(LogField.IOC_SOURCE_ID.key(), "source-17")
                .doesNotContainKey(LogField.IOC_STAGE.key());
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void concurrentRunsDoNotMixMdcCorrelation() throws Exception {
        var clock = Clock.systemUTC();
        var barrier = new CyclicBarrier(2);
        Map<String, String> observedRunIds = new ConcurrentHashMap<>();
        var runner = new PipelineRunner(
                FailurePolicy.collectAndContinue(),
                new LoggingPipelineObserver(),
                diagnostic -> observedRunIds.put(
                        String.valueOf(diagnostic.context().get("item")),
                        MDC.get(LogField.IOC_RUN_ID.key())),
                new DiagnosticFactory(clock));
        var pipeline = Pipeline.<String>start().then(new ConcurrentDiagnosticStage(clock, barrier));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> runAndCaptureRemainingMdc(runner, pipeline, clock, "run-a"));
            var second = executor.submit(() -> runAndCaptureRemainingMdc(runner, pipeline, clock, "run-b"));

            assertThat(first.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNullOrEmpty();
            assertThat(second.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNullOrEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("concurrent MDC workers should terminate")
                    .isTrue();
        }
        assertThat(observedRunIds).containsExactlyInAnyOrderEntriesOf(Map.of(
                "run-a", "run-a",
                "run-b", "run-b"));
    }

    @Test
    void restoresPreExistingMdcAfterRun() {
        var clock = Clock.systemUTC();
        var runner = new PipelineRunner(FailurePolicy.collectAndContinue(), new LoggingPipelineObserver());
        var pipeline = Pipeline.<String>start().then(new DiagnosticStage(clock));
        MDC.put(LogField.IOC_RUN_ID.key(), "outer-run");

        try {
            runner.run(Envelope.of("input", EnvelopeMeta.initial("inner-run", "source", clock)), pipeline);

            assertThat(MDC.get(LogField.IOC_RUN_ID.key())).isEqualTo("outer-run");
            assertThat(MDC.get(LogField.IOC_SOURCE_ID.key())).isNull();
            assertThat(MDC.get(LogField.IOC_STAGE.key())).isNull();
        } finally {
            MDC.clear();
        }
    }

    private Map<String, String> runAndCaptureRemainingMdc(PipelineRunner runner,
                                                           Pipeline<String, String> pipeline,
                                                           Clock clock,
                                                           String runId) {
        runner.run(Envelope.of(runId, EnvelopeMeta.initial(runId, runId + "-source", clock)), pipeline);
        return MDC.getCopyOfContextMap();
    }

    private record DiagnosticStage(Clock clock) implements Stage<String, String> {

        @Override
        public StageId name() {
            return new StageId("EXTRACT");
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            Diagnostic diagnostic = Diagnostic.builder(PipelineDiagnosticCodes.ITEM_SKIPPED, clock)
                    .with("item", "test")
                    .with("stage", "EXTRACT")
                    .with("reason", "test")
                    .build();
            return input.withDiagnostic(diagnostic);
        }
    }

    private record ConcurrentDiagnosticStage(Clock clock, CyclicBarrier barrier) implements Stage<String, String> {

        @Override
        public StageId name() {
            return new StageId("EXTRACT");
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            awaitPeer();
            Diagnostic diagnostic = Diagnostic.builder(PipelineDiagnosticCodes.ITEM_SKIPPED, clock)
                    .severity(DiagnosticSeverity.DEBUG)
                    .with("item", input.payload())
                    .with("stage", "EXTRACT")
                    .with("reason", "test")
                    .build();
            return input.withDiagnostic(diagnostic);
        }

        private void awaitPeer() {
            try {
                barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while synchronizing test runs", interrupted);
            } catch (BrokenBarrierException | TimeoutException failure) {
                throw new IllegalStateException("Failed to synchronize test runs", failure);
            }
        }
    }
}
