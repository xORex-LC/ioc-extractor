package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.processing.mapping.ArtifactFilter;
import com.iocextractor.processing.mapping.ColumnSpec;
import com.iocextractor.processing.mapping.ConfigurableRowMapper;
import com.iocextractor.adapter.out.sink.csv.IdValueProvider;
import com.iocextractor.processing.mapping.MappingValueException;
import com.iocextractor.processing.mapping.RowMapper;
import com.iocextractor.processing.mapping.ValueProvider;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.artifact.ArtifactIdentityDefinition;
import com.iocextractor.application.artifact.CanonicalArtifactIdentityResolver;
import com.iocextractor.application.artifact.policy.ArtifactWritePolicy;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.processing.model.ClassifiedIndicator;
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
    void routed_mapping_reuses_element_diagnostic_and_occurrence_position() {
        ValueProvider validating = classified -> {
            if (classified.indicator().value().contains("invalid")) {
                throw new MappingValueException("invalid address");
            }
            return classified.indicator().value();
        };
        var policy = new ArtifactWritePolicy(ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY,
                "name", Map.of("name", ArtifactWritePolicy.FieldUpdatePolicy.LATEST_REGISTERED_KEEP_EXISTING));
        var mapper = new ConfigurableRowMapper(List.of(
                new ColumnSpec("name", "checked", null, null, null)),
                Map.of("checked", validating), Map.of());
        var definition = new CsvArtifactDefinition("hashes", Set.of(IndicatorType.MD5),
                ArtifactFilter.none(), mapper, ArtifactIdStrategy.ASCENDING, 100, policy);
        var preparer = new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 100),
                new DiagnosticFactory(Clock.systemUTC()), "source-key",
                NoopPipelineDecisionTracer.INSTANCE);

        var rejected = preparer.prepareRouted(indicator("invalid"), Map.of(),
                new OccurrencePosition(7), 4);
        assertThat(rejected.value()).isEmpty();
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
            assertThat(diagnostic.context()).containsEntry("ordinal", 4)
                    .containsEntry("column", "name");
        });

        var accepted = preparer.prepareRouted(indicator("valid"), Map.of(),
                new OccurrencePosition(9), 5);
        assertThat(accepted.diagnostics()).isEmpty();
        assertThat(accepted.value()).isPresent().get().satisfies(row ->
                assertThat(row.orderedFieldPositions()).containsEntry("name", new OccurrencePosition(9)));
    }

    @Test
    void routed_plain_mapper_requires_no_field_overrides() {
        var preparer = preparer(new TestMapper(ignored -> { }));
        assertThat(preparer.name()).isEqualTo("hashes");

        var accepted = preparer.prepareRouted(indicator("plain"), Map.of(),
                new OccurrencePosition(4), 1);
        assertThat(accepted.value()).isPresent().get().satisfies(row ->
                assertThat(row.template().value("value")).isEqualTo("plain"));

        assertThatThrownBy(() -> preparer.prepareRouted(indicator("plain"),
                Map.of("value", indicator("alternate")), new OccurrencePosition(4), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Field views require a configurable row mapper");
    }

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
        assertThat(result.value().rows().snapshot()).hasSize(1);
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
        assertThatThrownBy(() -> preparer.prepareRouted(indicator("bad"),
                Map.of("value", indicator("different")), new OccurrencePosition(1), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Field views require a configurable row mapper: hashes");
    }

    @Test
    void routed_rows_preserve_oneshot_source_provenance_without_a_delivery_key() {
        var definition = new CsvArtifactDefinition("hashes", Set.of(IndicatorType.MD5),
                new TestMapper(ignored -> { }), ArtifactIdStrategy.ASCENDING, 100);
        var preparer = new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 100),
                new DiagnosticFactory(Clock.systemUTC()), null, NoopPipelineDecisionTracer.INSTANCE);

        var attributed = preparer.prepareRouted(indicator("md5", IndicatorType.MD5, "Feed Alpha"),
                Map.of(), new OccurrencePosition(1), 0);
        var unattributed = preparer.prepareRouted(indicator("md5", IndicatorType.MD5, null),
                Map.of(), new OccurrencePosition(2), 1);

        assertThat(attributed.value().orElseThrow().template().value("_source_key")).isEqualTo("Feed Alpha");
        assertThat(unattributed.value().orElseThrow().template().value("_source_key")).isEqualTo("oneshot");
        assertThat(attributed.diagnostics()).isEmpty();
        assertThat(unattributed.diagnostics()).isEmpty();
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

        var batch = preparer.prepare(List.of(indicator("md5", IndicatorType.MD5),
                indicator("sha1", IndicatorType.SHA1)));
        var accepted = preparer.prepareRouted(indicator("md5", IndicatorType.MD5),
                Map.of(), new OccurrencePosition(1), 0);
        var filtered = preparer.prepareRouted(indicator("sha1", IndicatorType.SHA1),
                Map.of(), new OccurrencePosition(2), 1);

        assertThat(accepted.value()).isPresent();
        assertThat(batch.value().rows().snapshot()).singleElement().satisfies(row ->
                assertThat(row.template()).isEqualTo(accepted.value().orElseThrow().template()));
        assertThat(filtered.value()).isEmpty();
        assertThat(filtered.diagnostics()).isEmpty();

        assertThat(tracer.decisions)
                .extracting(PipelineItemDecision::outcome)
                .containsExactly("routed", "filtered", "routed", "filtered");
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
                NoopPipelineDecisionTracer.INSTANCE);
        ClassifiedIndicator first = indicator("same-hash", IndicatorType.MD5, "first");
        ClassifiedIndicator unnamed = indicator("same-hash", IndicatorType.MD5, null);
        ClassifiedIndicator last = indicator("same-hash", IndicatorType.MD5, "last");

        var winners = new com.iocextractor.application.artifact.policy.ArtifactOccurrenceSelector()
                .<com.iocextractor.application.artifact.ArtifactRowKey, com.iocextractor.application.artifact.PreparedArtifactRow>
                        accumulator(policy, row -> row.template().value("name"));
        for (int index = 0; index < 3; index++) {
            var prepared = preparer.prepareRouted(List.of(first, unnamed, last).get(index), Map.of(),
                    new OccurrencePosition(index + 1), index);
            assertThat(prepared.diagnostics()).isEmpty();
            var row = prepared.value().orElseThrow();
            winners.add(identity.keyOf("aggregate", row.template()).orElseThrow(), row);
        }
        var result = com.iocextractor.diagnostics.result.Result.success(new com.iocextractor.application.artifact.ArtifactWritePlan(
                "aggregate", mapper.header(), winners.winners(), new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1)));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.value().rows().snapshot()).singleElement().satisfies(row -> {
            assertThat(row.template().value("name")).isEqualTo("last");
            assertThat(row.template().value("hash")).isEqualTo("same-hash");
            assertThat(row.orderedFieldPositions()).containsEntry("name", new OccurrencePosition(3));
        });
    }

    @Test
    void occurrencePolicyReportsOneBadMappingWithoutDiscardingIndependentCandidate() {
        ValueProvider validated = classified -> {
            if ("bad".equals(classified.indicator().value())) {
                throw new MappingValueException("invalid candidate");
            }
            return classified.indicator().value();
        };
        var mapper = new ConfigurableRowMapper(
                List.of(new ColumnSpec("value", "validated", null, null, null)),
                Map.of("validated", validated), Map.of());
        var policy = new ArtifactWritePolicy(
                ArtifactWritePolicy.DuplicateSelection.LAST_NONEMPTY, "value", Map.of());
        var definition = new CsvArtifactDefinition(
                "hashes", Set.of(IndicatorType.MD5), ArtifactFilter.none(), mapper,
                ArtifactIdStrategy.ASCENDING, 1, policy);
        var identity = new CanonicalArtifactIdentityResolver(List.of(
                new ArtifactIdentityDefinition("hashes", List.of("value"), true, 1)));
        var preparer = new CsvArtifactPreparer(definition,
                new ArtifactIdSequence(ArtifactIdStrategy.ASCENDING, 1),
                new DiagnosticFactory(Clock.systemUTC()), "source-key",
                NoopPipelineDecisionTracer.INSTANCE);
        var bad = indicator("bad");
        var good = indicator("good");

        var rejected = preparer.prepareRouted(bad, Map.of(), new OccurrencePosition(1), 0);
        var accepted = preparer.prepareRouted(good, Map.of(), new OccurrencePosition(2), 1);
        assertThat(rejected.diagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED));
        assertThat(rejected.value()).isEmpty();
        assertThat(accepted.diagnostics()).isEmpty();
        assertThat(accepted.value()).hasValueSatisfying(row ->
                assertThat(row.template().value("value")).isEqualTo("good"));
    }

    @Test
    void rowPreparationPreservesDuplicatesAndMappedCollisionsAndReservesEveryId() {
        var mapper = new ConfigurableRowMapper(List.of(
                new ColumnSpec("id", "id", null, null, null),
                new ColumnSpec("value", "constant", null, null, null),
                new ColumnSpec("source", "source", null, null, null)),
                Map.of("id", new IdValueProvider(), "constant", ignored -> "same-key",
                        "source", classified -> classified.indicator().source().label()), Map.of());
        var first = indicator("one", IndicatorType.MD5, "first");
        var second = indicator("two", IndicatorType.MD5, "second");
        for (List<ClassifiedIndicator> retained : List.of(List.of(first, first), List.of(first, second))) {
            var preparer = preparer(mapper);
            var plan = preparer.prepare(retained).value();
            assertThat(plan.rows().snapshot()).hasSize(2);
            assertThat(plan.rows().snapshot()).extracting(row -> row.template().value("source"))
                    .containsExactlyElementsOf(retained.stream().map(value -> value.indicator().source().label()).toList());
            assertThat(plan.materialize().rows()).extracting(row -> row.value("id"))
                    .containsExactly("100", "101");
            assertThat(preparer.prepare(List.of(first)).value().materialize().rows())
                    .singleElement().satisfies(row -> assertThat(row.value("id")).isEqualTo("102"));
        }
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
