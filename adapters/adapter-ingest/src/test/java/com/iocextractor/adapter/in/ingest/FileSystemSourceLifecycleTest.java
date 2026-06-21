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

        Path archived = lifecycle.archive(unit);
        assertThat(archived).exists();
        assertThat(unit.processingPath()).doesNotExist();

        Path failedSource = Files.writeString(tempDir.resolve("source2.html"), "ioc");
        var failedUnit = lifecycle.claim(failedSource, key, Instant.parse("2026-06-22T00:00:00Z"));
        Path failed = lifecycle.fail(failedUnit, "broken");
        assertThat(failed).exists();
        assertThat(failed.resolveSibling(failed.getFileName() + ".error"))
                .hasContent("broken");
    }
}
