package com.iocextractor.adapter.in.ingest;

import com.iocextractor.application.ingest.SourceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
}
