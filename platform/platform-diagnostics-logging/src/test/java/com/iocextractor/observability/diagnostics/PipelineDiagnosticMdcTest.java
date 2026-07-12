package com.iocextractor.observability.diagnostics;

import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.DiagnosticFactory;
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
import org.slf4j.MDC;

import java.time.Clock;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineDiagnosticMdcTest {

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
}
