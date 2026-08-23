package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemSourceLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void claims_archives_and_fails_sources_with_error_sidecar() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        var key = new SourceKey("ABC123");
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");

        var unit = lifecycle.claim(source, key, Instant.parse("2026-06-22T00:00:00Z"));
        assertThat(source).doesNotExist();
        assertThat(unit.processingPath()).exists();
        assertThat(unit.processingPath().getFileName().toString())
                .isEqualTo("abc123-source.html");

        Path archived = lifecycle.archive(unit);
        assertThat(archived).exists();
        assertThat(archived.getFileName().toString())
                .isEqualTo("abc123-source.html");
        assertThat(unit.processingPath()).doesNotExist();

        Path failedSource = Files.writeString(tempDir.resolve("source2.html"), "ioc");
        var failedUnit = lifecycle.claim(failedSource, key, Instant.parse("2026-06-22T00:00:00Z"));
        Path failed = lifecycle.fail(failedUnit, "broken");
        assertThat(failed).exists();
        assertThat(failed.getFileName().toString())
                .isEqualTo("abc123-source2.html");
        assertThat(failed.resolveSibling(failed.getFileName() + ".error"))
                .hasContent("broken");
    }

    @Test
    void lists_processing_sources_as_recovery_candidates() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        Files.createDirectories(tempDir.resolve("processing"));
        Files.writeString(tempDir.resolve("processing/abc123-source.html"), "ioc");
        Files.writeString(tempDir.resolve("processing/unkeyed.html"), "ignored");

        assertThat(lifecycle.findProcessingSources())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.key().value()).isEqualTo("abc123");
                    assertThat(source.processingPath().getFileName().toString()).isEqualTo("abc123-source.html");
                });
    }

    @Test
    void repeated_content_is_owned_by_distinct_recoverable_observation_names() throws Exception {
        var lifecycle = new FileSystemSourceLifecycle(
                tempDir.resolve("processing"),
                tempDir.resolve("done"),
                tempDir.resolve("failed"));
        var key = new SourceKey("abc123");
        Path first = Files.writeString(tempDir.resolve("first.html"), "ioc");
        Path second = Files.writeString(tempDir.resolve("second.html"), "ioc");

        var firstUnit = lifecycle.claim(
                first, new ObservationId("delivery-1"), key, Instant.EPOCH);
        var secondUnit = lifecycle.claim(
                second, new ObservationId("delivery-2"), key, Instant.EPOCH);

        assertThat(firstUnit.processingPath()).isNotEqualTo(secondUnit.processingPath());
        assertThat(lifecycle.findProcessingSources())
                .extracting(source -> source.observationId().value())
                .containsExactlyInAnyOrder("delivery-1", "delivery-2");
    }

    @Test
    void rejects_empty_lifecycle_directory_paths_at_construction() {
        Path empty = Path.of("");
        Path directory = Path.of("directory");

        assertThatThrownBy(() -> new FileSystemSourceLifecycle(empty, directory, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("processingDir must not be an empty path");
        assertThatThrownBy(() -> new FileSystemSourceLifecycle(directory, empty, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("doneDir must not be an empty path");
        assertThatThrownBy(() -> new FileSystemSourceLifecycle(directory, directory, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failedDir must not be an empty path");
    }
}
