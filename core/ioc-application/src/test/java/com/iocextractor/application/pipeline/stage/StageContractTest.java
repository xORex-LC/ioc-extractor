package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.application.port.out.IocSink;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageContractTest {

    @Test
    void concrete_stages_preserve_metadata_and_accumulated_diagnostics() {
        var diagnostic = Diagnostic.builder(PipelineDiagnosticCodes.STAGE_FAILED, StageTestSupport.CLOCK)
                .with("stage", "contract")
                .with("reason", "previous stage")
                .build();

        assertPreservesContract(
                new ReadSourceStage(source -> "source text"),
                StageTestSupport.commandEnvelope(false),
                diagnostic);
        assertPreservesContract(
                new RefangStage(text -> new RefangOutcome(text.replace("hxxp", "http"), List.of())),
                StageTestSupport.envelope(new SourceText("hxxp://example.com"), false),
                diagnostic);
        assertPreservesContract(
                new ExtractIndicatorsStage(text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())),
                StageTestSupport.envelope(new RefangedText(new RefangOutcome("example.com", List.of())), false),
                diagnostic);
        assertPreservesContract(
                new AttributeSourceStage((text, rawIndicators) ->
                        StageTestSupport.attributionOutcome(StageTestSupport.indicator("example.com")),
                        StageTestSupport.CLOCK),
                StageTestSupport.envelope(new ExtractedIndicators(
                        "example.com",
                        new ExtractionOutcome(
                                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())),
                        false),
                diagnostic);
        assertPreservesContract(
                new DeduplicateIndicatorsStage(true),
                StageTestSupport.envelope(StageTestSupport.attributedIndicators(
                                StageTestSupport.indicator("example.com")),
                        false),
                diagnostic);
        assertPreservesContract(
                new WriteArtifactsStage(List.of(new CountingSink())),
                StageTestSupport.envelope(new RetainedIndicators(
                        List.of(StageTestSupport.indicator("example.com")),
                        List.of(StageTestSupport.indicator("example.com"))),
                        false),
                diagnostic);
    }

    private <I, O> void assertPreservesContract(Stage<I, O> stage, Envelope<I> source, Diagnostic diagnostic) {
        var input = source.atStage(stage.name()).withDiagnostic(diagnostic);

        var output = stage.process(input);

        assertThat(output).isNotSameAs(input);
        assertThat(output.meta()).isEqualTo(input.meta());
        assertThat(output.diagnostics()).containsExactly(diagnostic);
        assertThat(input.payload()).isSameAs(source.payload());
    }

    private static final class CountingSink implements IocSink {

        @Override
        public String name() {
            return "test";
        }

        @Override
        public int write(List<Indicator> indicators) {
            return indicators.size();
        }
    }

}
