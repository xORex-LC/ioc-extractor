package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.ArtifactRowKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CsvStableIdIndexTest {

    @TempDir
    Path tempDir;

    @Test
    void preserves_existing_ids_after_reload() {
        Path path = tempDir.resolve(".ioc-id-index.csv");
        Clock clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC);
        var index = new CsvStableIdIndex(path, clock);

        var first = index.getOrCreate("masks", new ArtifactRowKey("mask:example.com"));
        var same = index.getOrCreate("masks", new ArtifactRowKey("mask:example.com"));
        index.save();

        var reloaded = new CsvStableIdIndex(path, clock);
        var afterReload = reloaded.getOrCreate("masks", new ArtifactRowKey("mask:example.com"));
        var next = reloaded.getOrCreate("masks", new ArtifactRowKey("mask:example.org"));

        assertThat(first.value()).isEqualTo(1L);
        assertThat(first.newlyCreated()).isTrue();
        assertThat(same.value()).isEqualTo(1L);
        assertThat(same.newlyCreated()).isFalse();
        assertThat(afterReload.value()).isEqualTo(1L);
        assertThat(afterReload.newlyCreated()).isFalse();
        assertThat(next.value()).isEqualTo(2L);
    }
}
