package com.iocextractor.application.pipeline.stage;

import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.Stage;
import com.iocextractor.application.pipeline.payload.ExtractedIndicators;
import com.iocextractor.application.pipeline.payload.RefangedText;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.pipeline.payload.PreparedArtifacts;
import com.iocextractor.application.pipeline.payload.SourceText;
import com.iocextractor.diagnostics.Diagnostic;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.domain.extract.RawIndicator;
import com.iocextractor.domain.extract.ExtractionOutcome;
import com.iocextractor.domain.refang.RefangOutcome;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.diagnostics.DiagnosticFactory;
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
                new ReadSourceStage(source -> "source text", StageTestSupport.DIAGNOSTICS),
                StageTestSupport.commandEnvelope(false),
                diagnostic);
        assertPreservesContract(
                new RefangStage(text -> new RefangOutcome(text.replace("hxxp", "http"), List.of()),
                        StageTestSupport.TRACER),
                StageTestSupport.envelope(new SourceText("hxxp://example.com"), false),
                diagnostic);
        assertPreservesContract(
                new ExtractIndicatorsStage(text -> new ExtractionOutcome(
                        List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of()),
                        StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER),
                StageTestSupport.envelope(new RefangedText(new RefangOutcome("example.com", List.of())), false),
                diagnostic);
        assertPreservesContract(
                new AttributeSourceStage((text, rawIndicators) ->
                        StageTestSupport.attributionOutcome(StageTestSupport.indicator("example.com")),
                        StageTestSupport.CLOCK, StageTestSupport.TRACER),
                StageTestSupport.envelope(new ExtractedIndicators(
                        "example.com",
                        new ExtractionOutcome(
                                List.of(new RawIndicator("example.com", IndicatorType.DOMAIN, 0)), List.of())),
                        false),
                diagnostic);
        assertPreservesContract(
                new DeduplicateIndicatorsStage(true, StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER),
                StageTestSupport.envelope(StageTestSupport.attributedIndicators(
                                StageTestSupport.indicator("example.com")),
                        false),
                diagnostic);
        assertPreservesContract(
                new ClassifyIndicatorsStage(indicator -> StageTestSupport.classifiedIndicator(indicator)
                        .classification(), StageTestSupport.DIAGNOSTICS, StageTestSupport.TRACER),
                StageTestSupport.envelope(StageTestSupport.deduplicatedIndicators(
                        StageTestSupport.indicator("example.com")), false),
                diagnostic);
        assertPreservesContract(
                new PrepareArtifactsStage(List.of()),
                StageTestSupport.envelope(new RetainedIndicators(0, List.of()), false),
                diagnostic);
        assertPreservesContract(
                new WriteArtifactsStage(new NoopRepository(), ignored -> { },
                        new DiagnosticFactory(StageTestSupport.CLOCK)),
                StageTestSupport.envelope(new PreparedArtifacts(0, 0, List.of()), true),
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

    private static final class NoopRepository implements CanonicalArtifactRepository {
        @Override
        public CanonicalArtifact load(String artifactName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            return new CanonicalWriteResult(0, 0);
        }
    }

}
