package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.CanonicalArtifact;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.port.out.artifact.ArtifactProjectionCommand;
import com.iocextractor.application.port.out.artifact.CanonicalArtifactStreamReader;
import com.iocextractor.common.IocExtractorException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.DiagnosticImpact;
import com.iocextractor.diagnostics.DiagnosticSeverity;
import com.iocextractor.diagnostics.codes.SinkDiagnosticCodes;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class CsvArtifactProjectionIT {

    private static final Charset CP1251 = Charset.forName("windows-1251");
    private static final DiagnosticFactory DIAGNOSTICS = new DiagnosticFactory(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private static final ExportFormat FORMAT = new ExportFormat("csv", "UTF-8", ";", "\"", "NULL");

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
        var format = new ExportFormat("csv", CP1251.name(), ";", "\"", "NULL😀");
        Path path = tempDir.resolve("masks.csv");
        var projection = new CsvArtifactProjection(
                new SnapshotRepository(artifact),
                Map.of("masks", header),
                Map.of("masks", path),
                format,
                DIAGNOSTICS);

        var outcome = projection.project(new ArtifactProjectionCommand("run-3", "masks"));

        assertThat(outcome.diagnostics()).singleElement().satisfies(diagnostic -> assertThat(diagnostic.context())
                .containsEntry("affectedValues", 1)
                .containsEntry("affectedRows", 1)
                .containsEntry("affectedHeaderValues", 0));
    }

    @Test
    void writes_parentless_relative_projection_path() throws Exception {
        var header = List.of("id", "value");
        var artifact = new CanonicalArtifact("masks", header, List.of(row(header, "1", "value")));
        Path path = Path.of("projection-" + System.nanoTime() + ".csv");

        try {
            var outcome = projection(artifact, path, StandardCharsets.UTF_8)
                    .project(new ArtifactProjectionCommand("run-parentless", "masks"));

            assertThat(outcome.projectedRows()).isOne();
            assertThat(path).hasContent("\"id\";\"value\"\n\"1\";\"value\"\n");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void rejects_projection_root_with_explicit_adapter_error() {
        var header = List.of("id", "value");
        var artifact = new CanonicalArtifact("masks", header, List.of(row(header, "1", "value")));

        assertThatThrownBy(() -> projection(artifact, Path.of("/"), StandardCharsets.UTF_8)
                .project(new ArtifactProjectionCommand("run-root", "masks")))
                .isInstanceOf(IocExtractorException.class)
                .hasMessage("Artifact projection path must name a CSV file: /");
    }

    @Test
    void preserves_installed_projection_when_stream_fails() throws Exception {
        var header = List.of("id", "value");
        Path path = tempDir.resolve("masks.csv");
        Files.writeString(path, "previous projection\n");
        var failure = new IllegalStateException("cursor failed");
        CanonicalArtifactStreamReader failingReader = (artifactName, consumer) -> {
            consumer.accept(row(header, "1", "partial"));
            throw failure;
        };
        var projection = new CsvArtifactProjection(
                failingReader,
                Map.of("masks", header),
                Map.of("masks", path),
                FORMAT,
                DIAGNOSTICS);

        assertThatThrownBy(() -> projection.project(new ArtifactProjectionCommand("run-failed", "masks")))
                .isSameAs(failure);
        assertThat(path).hasContent("previous projection\n");
        try (var children = Files.list(tempDir)) {
            assertThat(children).containsExactly(path);
        }
    }

    private CsvArtifactProjection projection(CanonicalArtifact artifact, Path path, Charset charset) {
        return new CsvArtifactProjection(
                new SnapshotRepository(artifact),
                Map.of(artifact.name(), artifact.header()),
                Map.of(artifact.name(), path),
                new ExportFormat("csv", charset.name(), ";", "\"", "NULL"),
                DIAGNOSTICS);
    }

    private ArtifactRow row(List<String> header, String... values) {
        var byColumn = new LinkedHashMap<String, String>();
        for (int index = 0; index < header.size(); index++) {
            byColumn.put(header.get(index), values[index]);
        }
        return ArtifactRow.ordered(byColumn);
    }

    private record SnapshotRepository(CanonicalArtifact artifact) implements CanonicalArtifactStreamReader {

        @Override
        public int stream(String artifactName,
                          com.iocextractor.application.port.out.artifact.CanonicalArtifactRowConsumer consumer) {
            assertThat(artifactName).isEqualTo(artifact.name());
            artifact.rows().forEach(consumer::accept);
            return artifact.rows().size();
        }
    }
}
