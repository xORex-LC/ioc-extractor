package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.artifact.CanonicalWriteResult;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactRepository;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvArtifactProjectionTest {

    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setNullString("NULL")
            .setQuoteMode(QuoteMode.ALL_NON_NULL)
            .build();

    @TempDir
    Path tempDir;

    @Test
    void reports_exact_lossy_value_row_and_header_counts_without_raw_values() throws Exception {
        var header = List.of("id", "value", "comment😀");
        var artifact = new CanonicalArtifact("masks", header, List.of(
                row(header, "1", "alpha😀beta🚀", "ok"),
                row(header, "2", "alpha😀beta🚀", "also漢"),
                row(header, "3", "ФСТЭК", "чисто")));
        Path path = tempDir.resolve("masks.csv");
        var projection = projection(artifact, path, CP1251);

        var outcome = projection.project(new ArtifactProjectionCommand("run-1", "masks"));

        assertThat(outcome.projectedRows()).isEqualTo(3);
        assertThat(outcome.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(SinkDiagnosticCodes.CHARSET_UNMAPPABLE);
            assertThat(diagnostic.code().impact()).isEqualTo(DiagnosticImpact.OPERATION);
            assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARN);
            assertThat(diagnostic.context()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "runId", "run-1",
                    "artifact", "masks",
                    "path", path.toString(),
                    "charset", "windows-1251",
                    "affectedValues", 3,
                    "affectedRows", 2,
                    "affectedHeaderValues", 1));
            assertThat(diagnostic.context().toString())
                    .doesNotContain("alpha", "beta", "also", "ФСТЭК", "😀", "🚀", "漢");
        });
        String csv = Files.readString(path, CP1251);
        assertThat(csv).doesNotContain("😀", "🚀", "漢").contains("?");
    }

    @Test
    void returns_clean_outcome_when_every_value_is_representable() {
        var header = List.of("id", "value");
        var artifact = new CanonicalArtifact("masks", header, List.of(
                row(header, "1", "anything 😀 ФСТЭК")));
        var projection = projection(artifact, tempDir.resolve("masks.csv"), StandardCharsets.UTF_8);

        var outcome = projection.project(new ArtifactProjectionCommand("run-2", "masks"));

        assertThat(outcome.projectedRows()).isOne();
        assertThat(outcome.diagnostics()).isEmpty();
    }

    @Test
    void counts_an_unrepresentable_null_marker_as_one_data_value() {
        var header = List.of("id", "value");
        var artifact = new CanonicalArtifact("masks", header, List.of(row(header, "1", null)));
        var format = FORMAT.builder().setNullString("NULL😀").build();
        Path path = tempDir.resolve("masks.csv");
        var projection = new CsvArtifactProjection(
                new SnapshotRepository(artifact),
                Map.of("masks", header),
                Map.of("masks", path),
                format,
                CP1251,
                DIAGNOSTICS);

        var outcome = projection.project(new ArtifactProjectionCommand("run-3", "masks"));

        assertThat(outcome.diagnostics()).singleElement().satisfies(diagnostic -> assertThat(diagnostic.context())
                .containsEntry("affectedValues", 1)
                .containsEntry("affectedRows", 1)
                .containsEntry("affectedHeaderValues", 0));
    }

    private CsvArtifactProjection projection(CanonicalArtifact artifact, Path path, Charset charset) {
        return new CsvArtifactProjection(
                new SnapshotRepository(artifact),
                Map.of(artifact.name(), artifact.header()),
                Map.of(artifact.name(), path),
                FORMAT,
                charset,
                DIAGNOSTICS);
    }

    private ArtifactRow row(List<String> header, String... values) {
        var byColumn = new LinkedHashMap<String, String>();
        for (int index = 0; index < header.size(); index++) {
            byColumn.put(header.get(index), values[index]);
        }
        return ArtifactRow.ordered(byColumn);
    }

    private record SnapshotRepository(CanonicalArtifact artifact) implements CanonicalArtifactRepository {

        @Override
        public CanonicalArtifact load(String artifactName) {
            assertThat(artifactName).isEqualTo(artifact.name());
            return artifact;
        }

        @Override
        public CanonicalWriteResult write(String artifactName, CanonicalArtifact artifact) {
            throw new UnsupportedOperationException();
        }
    }
}
