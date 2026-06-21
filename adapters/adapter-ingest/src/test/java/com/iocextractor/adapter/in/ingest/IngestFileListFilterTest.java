package com.iocextractor.adapter.in.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestFileListFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void accepts_only_included_non_excluded_files_after_quiet_period() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-06-22T00:00:10Z"), ZoneOffset.UTC);
        var accepted = Files.writeString(tempDir.resolve("source.html"), "data");
        var fresh = Files.writeString(tempDir.resolve("fresh.html"), "data");
        var excluded = Files.writeString(tempDir.resolve("ignored.tmp"), "data");
        Files.setLastModifiedTime(accepted, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:00Z")));
        Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:08Z")));
        Files.setLastModifiedTime(excluded, java.nio.file.attribute.FileTime.from(Instant.parse("2026-06-22T00:00:00Z")));

        var filter = new IngestFileListFilter(List.of("*.html"), List.of("*.tmp"),
                Duration.ofSeconds(5), clock);

        assertThat(filter.filterFiles(tempDir.toFile().listFiles()))
                .containsExactly(accepted.toFile());
    }
}
