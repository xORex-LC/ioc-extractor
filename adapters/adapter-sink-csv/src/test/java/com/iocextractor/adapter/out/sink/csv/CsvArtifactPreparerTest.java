package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdSequence;
import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvArtifactPreparerTest {

    @Test
    void continuesOnlyAfterTypedRowMappingFailureAndDefersIds() {
        var preparer = preparer(new TestMapper(value -> {
            if ("bad".equals(value)) {
                throw new RowMappingException("invalid row");
            }
        }));

        var result = preparer.prepare(List.of(indicator("bad"), indicator("good")));

        assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.ROW_MAPPING_FAILED);
                    assertThat(diagnostic.context())
                            .containsEntry("indicator", "bad")
                            .containsEntry("type", IndicatorType.MD5)
                            .containsEntry("source", "source-key")
                            .containsEntry("artifact", "hashes")
                            .containsEntry("ordinal", 0);
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

    private CsvArtifactPreparer preparer(RowMapper mapper) {
        var definition = new CsvArtifactDefinition(
                "hashes", Set.of(IndicatorType.MD5), mapper, ArtifactIdStrategy.ASCENDING, 100);
        return new CsvArtifactPreparer(
                definition,
                new ArtifactIdSequence(definition.idStrategy(), definition.idStart()),
                new DiagnosticFactory(Clock.systemUTC()),
                "source-key");
    }

    private ClassifiedIndicator indicator(String value) {
        var indicator = new Indicator(value, IndicatorType.MD5, new SourceContext("source", null));
        var features = new IndicatorFeatures(value, value, false, false, false, HostKind.UNKNOWN);
        return new ClassifiedIndicator(indicator,
                new ClassificationDecision(features, -1, List.of(), new MaskMatch(null, null)));
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
