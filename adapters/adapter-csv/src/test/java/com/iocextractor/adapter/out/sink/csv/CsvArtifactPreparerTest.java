package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactPreparationBatch;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicatorOccurrence;
import com.iocextractor.application.observability.NoopPipelineDecisionTracer;
import com.iocextractor.application.observability.PipelineItemDecision;
import com.iocextractor.application.port.out.observability.PipelineDecisionTracer;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import com.iocextractor.domain.classify.ClassificationDecision;
import com.iocextractor.domain.feature.HostKind;
import com.iocextractor.domain.feature.IndicatorFeatures;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import com.iocextractor.domain.model.MaskMatch;
import com.iocextractor.domain.model.SourceContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvArtifactPreparerTest {

    @Test
    void continuesOnlyAfterTypedRowMappingFailureAndDefersIds() {
        ValueProvider validatingProvider = classified -> {
            if ("bad".equals(classified.indicator().value())) {
                throw new MappingValueException("invalid row");
            }
            return classified.indicator().value();
        };
        var mapper = new ConfigurableRowMapper(
                List.of(
                        new ColumnSpec("id", "id", null, null, null),
                        new ColumnSpec("value", "validated", null, null, null)),
                Map.of("id", new IdValueProvider(), "validated", validatingProvider),
                Map.of());
        var preparer = preparer(mapper);

        var result = preparer.prepare(List.of(indicator("bad"), indicator("good")));

        assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
                    assertThat(diagnostic.context())
                            .containsEntry("indicator", "bad")
                            .containsEntry("type", IndicatorType.MD5)
                            .containsEntry("source", "source-key")
                            .containsEntry("artifact", "hashes")
                            .containsEntry("ordinal", 0)
                            .containsEntry("column", "value")
                            .containsEntry("componentKind", "provider")
                            .containsEntry("componentName", "validated");
                    assertThat(diagnostic.cause()).isEmpty();
                });
        assertThat(result.value().rows()).hasSize(1);
        assertThat(result.value().materialize().rows()).singleElement().satisfies(row -> {
            assertThat(row.value("id")).isEqualTo("100");
            assertThat(row.value("value")).isEqualTo("good");
        });
    }

    @Test
    void propagatesUnexpectedMapperDefect() {
        var preparer = preparer(new TestMapper(value -> {
            throw new IllegalStateException("mapper defect");
        }));

        assertThatThrownBy(() -> preparer.prepare(List.of(indicator("bad"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mapper defect");
    }

    @Test
    void tracesRouteDecisionsWithoutRecomputingClassification() {
        var tracer = new RecordingTracer();
        var definition = new CsvArtifactDefinition(
                "hashes", Set.of(IndicatorType.MD5), new TestMapper(ignored -> { }),
                ArtifactIdStrategy.ASCENDING, 100);
        var preparer = new CsvArtifactPreparer(
                definition,
                new ArtifactIdSequence(definition.idStrategy(), definition.idStart()),
                new DiagnosticFactory(Clock.systemUTC()),
                "source-key",
                tracer);

        preparer.prepare(List.of(indicator("md5", IndicatorType.MD5), indicator("sha1", IndicatorType.SHA1)));

        assertThat(tracer.decisions)
                .extracting(PipelineItemDecision::outcome)
                .containsExactly("routed", "filtered");
        assertThat(tracer.decisions).allSatisfy(decision ->
                assertThat(decision.artifact()).isEqualTo("hashes"));
    }

    @Test
    void occurrence_policy_selects_the_last_nonempty_whole_row_by_mapped_identity() {
        var policy = new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY,
                "name",
                Map.of("name", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
        RowMapper mapper = new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("name", "hash");
            }

            @Override
            public List<String> toRow(ClassifiedIndicator classified) {
                return java.util.Arrays.asList(
                        classified.indicator().source().label(), classified.indicator().value());
            }

            @Override
            public Optional<String> idColumn() {
                return Optional.empty();
            }
        };
        var definition = new CsvArtifactDefinition(
                "aggregate", Set.of(IndicatorType.MD5), ArtifactFilter.none(), mapper,
                ArtifactIdStrategy.ASCENDING, 1, policy);
        var identity = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("aggregate", List.of("hash"), true, 1)));
        var preparer = new CsvArtifactPreparer(
                definition, new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), "source-key",
                NoopPipelineDecisionTracer.INSTANCE, identity);
        ClassifiedIndicator first = indicator("same-hash", IndicatorType.MD5, "first");
        ClassifiedIndicator unnamed = indicator("same-hash", IndicatorType.MD5, null);
        ClassifiedIndicator last = indicator("same-hash", IndicatorType.MD5, "last");

        var result = preparer.prepare(new ArtifactPreparationBatch(
                List.of(first),
                List.of(
                        new ClassifiedIndicatorOccurrence(first, new OccurrencePosition(1)),
                        new ClassifiedIndicatorOccurrence(unnamed, new OccurrencePosition(2)),
                        new ClassifiedIndicatorOccurrence(last, new OccurrencePosition(3)))));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.value().rows()).singleElement().satisfies(row -> {
            assertThat(row.template().value("name")).isEqualTo("last");
            assertThat(row.template().value("hash")).isEqualTo("same-hash");
            assertThat(row.orderedFieldPositions()).containsEntry("name", new OccurrencePosition(3));
        });
    }

    private CsvArtifactPreparer preparer(RowMapper mapper) {
        var definition = new CsvArtifactDefinition(
                "hashes", Set.of(IndicatorType.MD5), mapper, ArtifactIdStrategy.ASCENDING, 100);
        return new CsvArtifactPreparer(
                definition,
                new ArtifactIdSequence(definition.idStrategy(), definition.idStart()),
                new DiagnosticFactory(Clock.systemUTC()),
                "source-key",
                NoopPipelineDecisionTracer.INSTANCE);
    }

    private ClassifiedIndicator indicator(String value) {
        return indicator(value, IndicatorType.MD5);
    }

    private ClassifiedIndicator indicator(String value, IndicatorType type) {
        return indicator(value, type, "source");
    }

    private ClassifiedIndicator indicator(String value, IndicatorType type, String source) {
        var indicator = new Indicator(value, type, new SourceContext(source, null));
        var features = new IndicatorFeatures(value, value, false, false, false, HostKind.UNKNOWN);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, -1, List.of(), new MaskMatch(null, null)));
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

    @FunctionalInterface
    private interface MappingBehavior {

        void accept(String value);
    }

    private record TestMapper(MappingBehavior behavior) implements RowMapper {

        @Override
        public List<String> header() {
            return List.of("id", "value");
        }

        @Override
        public List<String> toRow(ClassifiedIndicator classified) {
            behavior.accept(classified.indicator().value());
            return java.util.Arrays.asList(null, classified.indicator().value());
        }

        @Override
        public Optional<String> idColumn() {
            return Optional.of("id");
        }
    }
}
