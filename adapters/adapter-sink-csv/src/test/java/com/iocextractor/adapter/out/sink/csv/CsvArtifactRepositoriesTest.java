package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRow;
import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.domain.model.Indicator;
import com.iocextractor.domain.model.IndicatorType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CsvArtifactRepositoriesTest {

    @TempDir
    Path tempDir;

    @Test
    void reads_partition_rows_and_writes_canonical_artifact() throws Exception {
        Path partition = tempDir.resolve("partitions/masks/2026-06-22/A.csv");
        Files.createDirectories(partition.getParent());
        Files.writeString(partition, "\"id\";\"mask\"\r\n\"1\";\"example.com\"\r\n");
        Path canonical = tempDir.resolve("canonical/masks.csv");
        var repository = repository(canonical);
        var record = new IngestionRecord(new SourceKey("A"), IngestionStatus.SOURCE_ARCHIVED,
                Path.of("inbox/A.html"), Path.of("processing/A.html"), Path.of("done/A.html"),
                List.of(partition), Instant.parse("2026-06-22T00:00:00Z"),
                Instant.parse("2026-06-22T00:00:00Z"), null);

        var partitions = repository.readPartitions(List.of(record));

        assertThat(partitions).singleElement()
                .satisfies(item -> {
                    assertThat(item.artifactName()).isEqualTo("masks");
                    assertThat(item.rows()).singleElement()
                            .extracting(row -> row.value("mask"))
                            .isEqualTo("example.com");
                });

        repository.write("masks", new CanonicalArtifact("masks", List.of("id", "mask"),
                List.of(new ArtifactRow(Map.of("id", "10", "mask", "example.org")))));

        assertThat(Files.readString(canonical)).contains("\"10\";\"example.org\"");
    }

    private CsvArtifactRepositories repository(Path canonicalPath) {
        RowMapper mapper = new RowMapper() {
            @Override
            public List<String> header() {
                return List.of("id", "mask");
            }

            @Override
            public List<String> toRow(long id, Indicator indicator) {
                return List.of(Long.toString(id), indicator.value());
            }
        };
        var definition = new CsvArtifactDefinition("masks", Set.of(IndicatorType.DOMAIN), mapper,
                IdGenerator.Strategy.ASCENDING, 1L);
        return new CsvArtifactRepositories(
                List.of(definition),
                Map.of("masks", canonicalPath),
                readFormat(),
                writeFormat());
    }

    private CSVFormat readFormat() {
        return CSVFormat.Builder.create()
                .setDelimiter(';')
                .setQuote('"')
                .setNullString("NULL")
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
    }

    private CSVFormat writeFormat() {
        return CSVFormat.Builder.create()
                .setDelimiter(';')
                .setQuote('"')
                .setNullString("NULL")
                .setQuoteMode(QuoteMode.ALL_NON_NULL)
                .setRecordSeparator("\r\n")
                .build();
    }
}
