package com.iocextractor.application.pipeline;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
        var seenStages = new ArrayList<StageName>();
        var pipeline = Pipeline.<String>start()
                .then(new RecordingStage(StageName.READ_SOURCE, "-read", seenStages))
                .then(new RecordingStage(StageName.REFANG, "-refang", seenStages));

        var output = new PipelineRunner(FailurePolicy.failFast())
                .run(Envelope.of("start", meta()), pipeline);

        assertThat(output.payload()).isEqualTo("start-read-refang");
        assertThat(seenStages).containsExactly(StageName.READ_SOURCE, StageName.REFANG);
        assertThat(output.meta().stage()).isEqualTo(StageName.REFANG);
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
                .then(new RecordingStage(StageName.REFANG, "-next", new ArrayList<>()));

        var output = new PipelineRunner(FailurePolicy.collectAndContinue())
                .run(Envelope.of("start", meta()), pipeline);

        assertThat(output.payload()).isEqualTo("start-next");
        assertThat(output.diagnostics()).contains(diagnostic);
    }

    private EnvelopeMeta meta() {
        return EnvelopeMeta.initial("run-1", Path.of("source.html"), false, CLOCK);
    }

    private Diagnostic diagnostic(DiagnosticSeverity severity) {
        return Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, CLOCK)
                .severity(severity)
                .with("stage", "test")
                .with("reason", "failed")
                .build();
    }

    private record RecordingStage(StageName name, String suffix, List<StageName> seenStages)
            implements Stage<String, String> {

        @Override
        public Envelope<String> process(Envelope<String> input) {
            seenStages.add(input.meta().stage());
            return input.withPayload(input.payload() + suffix);
        }
    }

    private record DiagnosticStage(Diagnostic diagnostic) implements Stage<String, String> {

        @Override
        public StageName name() {
            return StageName.EXTRACT;
        }

        @Override
        public Envelope<String> process(Envelope<String> input) {
            return input.withDiagnostic(diagnostic);
        }
    }
}
