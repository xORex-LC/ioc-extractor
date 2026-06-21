package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionedCsvSinkFactoryTest {

    @Test
    void resolves_partition_paths_by_artifact_day_and_source_key() {
        RowMapper mapper = new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("value");
            }

            @Override
            public List<String> toRow(long id, Indicator indicator) {
                return List.of(indicator.value());
            }
        };
        var factory = new PartitionedCsvSinkFactory(
                Path.of("partitions"),
                List.of(new CsvArtifactDefinition("masks", Set.of(IndicatorType.DOMAIN),
                        mapper, IdGenerator.Strategy.ASCENDING, 1)),
                CSVFormat.DEFAULT);

        var result = factory.createFor(new SourceUnit(
                new SourceKey("ABC123"),
                Path.of("inbox/source.html"),
                Path.of("processing/source.html"),
                Instant.parse("2026-06-22T10:15:30Z")));

        assertThat(result.paths()).containsExactly(Path.of("partitions/masks/2026-06-22/abc123.csv"));
        assertThat(result.sinks()).hasSize(1);
    }
}
