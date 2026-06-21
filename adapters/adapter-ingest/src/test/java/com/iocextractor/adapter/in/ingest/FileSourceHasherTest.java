package com.iocextractor.adapter.in.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSourceHasherTest {

    @TempDir
    Path tempDir;

    @Test
    void computes_lowercase_sha256_source_key() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.html"), "ioc");

        assertThat(new FileSourceHasher().sha256(source).value())
                .isEqualTo("7354a0024740d89096dc6137ff3bb47df328ab8ea22f20e88c059d387e58aeae");
    }
}
