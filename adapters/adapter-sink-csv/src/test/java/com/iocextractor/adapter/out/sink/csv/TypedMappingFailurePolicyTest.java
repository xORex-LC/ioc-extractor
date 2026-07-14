package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.pipeline.CompletionStatus;
import com.iocextractor.application.pipeline.payload.ArtifactWriteSummary;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.RetainedIndicators;
import com.iocextractor.application.pipeline.stage.PrepareArtifactsStage;
import com.iocextractor.application.pipeline.stage.WriteArtifactsStage;
import com.iocextractor.application.port.out.artifact.ArtifactPreparer;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.PipelineDiagnosticCodes;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.diagnostics.result.FailurePolicy;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import com.iocextractor.platform.etl.Envelope;
import com.iocextractor.platform.etl.EnvelopeMeta;
import com.iocextractor.platform.etl.NoopPipelineObserver;
import com.iocextractor.platform.etl.Pipeline;
import com.iocextractor.platform.etl.PipelineRunner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypedMappingFailurePolicyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);
    private static final long FIRST_ID = 100;

    @Test
    void collect_commits_only_valid_rows_and_returns_degraded_completion() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository();
        var projections = new AtomicInteger();

        var result = runner(FailurePolicy.collectAndContinue()).runWithOutcome(
                input(), pipeline(preparer(ids, typedRejectingProvider()), repository, projections));

        assertThat(CompletionStatus.from(result.diagnosticSummary()))
                .isEqualTo(CompletionStatus.COMPLETED_WITH_ERRORS);
        assertThat(repository.writes).isEqualTo(1);
        assertThat(repository.artifact.rows()).singleElement().satisfies(row -> {
            assertThat(row.value("id")).isEqualTo("100");
            assertThat(row.value("mask")).isEqualTo("good.example");
        });
        assertThat(projections).hasValue(1);
        assertThat(result.envelope().diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
            assertThat(diagnostic.context())
                    .containsEntry("column", "mask")
                    .containsEntry("componentKind", "provider")
                    .containsEntry("componentName", "validated")
                    .containsEntry("indicator", "bad.example");
        });
    }

    @Test
    void fail_fast_rejects_before_id_reservation_write_and_projection() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository();
        var projections = new AtomicInteger();

        assertThatThrownBy(() -> runner(FailurePolicy.failFast()).run(
                input(), pipeline(preparer(ids, typedRejectingProvider()), repository, projections)))
                .isInstanceOf(DiagnosticException.class)
                .extracting("diagnostic.code")
                .isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);

        assertThat(repository.writes).isZero();
        assertThat(projections).hasValue(0);
        assertThat(ids.reserve(1).start()).isEqualTo(FIRST_ID);
    }

    @Test
    void unexpected_provider_defect_remains_run_failure() {
        var ids = new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, FIRST_ID);
        var repository = new RecordingRepository();
        var projections = new AtomicInteger();
        ValueProvider brokenProvider = ignored -> {
            throw new IllegalStateException("provider invariant broken");
        };

        assertThatThrownBy(() -> runner(FailurePolicy.collectAndContinue()).run(
                input(), pipeline(preparer(ids, brokenProvider), repository, projections)))
                .isInstanceOfSatisfying(DiagnosticException.class, failure -> {
                    assertThat(failure.diagnostic().code()).isEqualTo(PipelineDiagnosticCodes.STAGE_FAILED);
                    assertThat(failure).hasRootCauseMessage("provider invariant broken");
                });

        assertThat(repository.writes).isZero();
        assertThat(projections).hasValue(0);
        assertThat(ids.reserve(1).start()).isEqualTo(FIRST_ID);
    }

    private ValueProvider typedRejectingProvider() {
        return classified -> {
            if (classified.indicator().value().startsWith("bad")) {
                throw new MappingValueException("value violates provider contract");
            }
            return classified.indicator().value();
        };
    }

    private ArtifactPreparer preparer(ArtifactIdSequence ids, ValueProvider provider) {
        var mapper = new ConfigurableRowMapper(
                List.of(
                        new ColumnSpec("id", "id", null, null, null),
                        new ColumnSpec("mask", "validated", null, null, null)),
                Map.of("id", new IdValueProvider(), "validated", provider),
                Map.of());
        var definition = new CsvArtifactDefinition(
                "masks", Set.of(IndicatorType.DOMAIN), mapper,
                ArtifactIdStrategy.ASCENDING, FIRST_ID);
        return new CsvArtifactPreparer(
                definition, ids, new DiagnosticFactory(CLOCK), "source-key",
                NoopPipelineDecisionTracer.INSTANCE);
    }

    private Pipeline<RetainedIndicators, ArtifactWriteSummary> pipeline(
            ArtifactPreparer preparer,
            CanonicalArtifactRepository repository,
            AtomicInteger projections) {
        return Pipeline.<RetainedIndicators>start()
                .then(new PrepareArtifactsStage(List.of(preparer)))
                .then(new WriteArtifactsStage(repository, ignored -> projections.incrementAndGet(),
                        new DiagnosticFactory(CLOCK)));
    }

    private PipelineRunner runner(FailurePolicy policy) {
        return new PipelineRunner(policy, new NoopPipelineObserver(),
                new CollectingDiagnosticSink(), new DiagnosticFactory(CLOCK));
    }

    private Envelope<RetainedIndicators> input() {
        var indicators = List.of(classified("bad.example"), classified("good.example"));
        return Envelope.of(
                new RetainedIndicators(indicators.size(), indicators),
                EnvelopeMeta.initial("run-17", "source", CLOCK));
    }

    private ClassifiedIndicator classified(String value) {
        var indicator = new Indicator(
                value, IndicatorType.DOMAIN, new SourceContext("source", null));
        var features = new IndicatorFeatures(
                value, value, false, false, false, HostKind.REGISTRABLE);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(
                        features, 0, List.of(), new MaskMatch("u:hAS", "h:dAS")));
    }

    private static final class RecordingRepository implements CanonicalArtifactRepository {
        private int writes;
        private CanonicalArtifact artifact;

        @Override
        public CanonicalArtifact load(String artifactName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            writes++;
            this.artifact = artifact;
            return new CanonicalWriteResult(artifact.rows().size(), artifact.rows().size());
        }
    }
}
