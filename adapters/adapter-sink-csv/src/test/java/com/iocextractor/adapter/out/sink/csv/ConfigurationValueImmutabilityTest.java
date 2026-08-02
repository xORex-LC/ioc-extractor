package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactIdStrategy;
import com.iocextractor.application.pipeline.payload.ClassifiedIndicator;
import com.iocextractor.domain.model.IndicatorType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationValueImmutabilityTest {

    @Test
    void columnSpecSnapshotsNullableTransformListIncludingNullElements() {
        var transforms = new ArrayList<>(Arrays.asList("lower", null));

        var spec = new ColumnSpec("mask", "value", null, null, transforms);
        transforms.clear();

        assertThat(spec.transform()).containsExactly("lower", null);
        assertThatThrownBy(() -> spec.transform().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ColumnSpec("mask", "value", null, null, null).transform()).isNull();
    }

    @Test
    void artifactDefinitionSnapshotsAcceptedTypes() {
        var accepts = EnumSet.of(IndicatorType.DOMAIN);

        var definition = new CsvArtifactDefinition(
                "masks", accepts, ArtifactFilter.none(), rowMapper(),
                ArtifactIdStrategy.ASCENDING, 0);
        accepts.add(IndicatorType.URL);

        assertThat(definition.accepts()).containsExactly(IndicatorType.DOMAIN);
        assertThatThrownBy(() -> definition.accepts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new CsvArtifactDefinition(
                "masks", null, ArtifactFilter.none(), rowMapper(),
                ArtifactIdStrategy.ASCENDING, 0).accepts()).isNull();
    }

    private RowMapper rowMapper() {
        return new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("value");
            }

            @Override
            public List<String> toRow(ClassifiedIndicator indicator) {
                return List.of(indicator.indicator().value());
            }
        };
    }
}
