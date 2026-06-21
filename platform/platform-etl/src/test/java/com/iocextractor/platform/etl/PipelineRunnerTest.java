package com.iocextractor.platform.etl;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
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
        var pipeline = Pipeline.<String>start()
                .then(new DiagnosticStage(diagnostic));

        assertThatThrownBy(() -> new PipelineRunner(FailurePolicy.failFast())
                .run(Envelope.of("start", meta()), pipeline))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic")
                .isEqualTo(diagnostic);
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
    void opens_and_closes_observer_scope_around_stage_execution() {
        var seen = new ArrayList<String>();
        var events = new ArrayList<String>();
        var pipeline = Pipeline.<String>start()
                .then(new ObserverRecordingStage(seen));

        new PipelineRunner(FailurePolicy.failFast(), new RecordingObserver(events))
                .run(Envelope.of("start", meta().withAttribute("mode", "daemon")), pipeline);

        assertThat(seen).containsExactly("READ_SOURCE");
        assertThat(events).containsExactly("open:READ_SOURCE", "started:READ_SOURCE", "completed:READ_SOURCE", "close");
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
        public AutoCloseable openStage(EnvelopeMeta meta) {
            events.add("open:" + meta.stage().value());
            return () -> events.add("close");
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
}
