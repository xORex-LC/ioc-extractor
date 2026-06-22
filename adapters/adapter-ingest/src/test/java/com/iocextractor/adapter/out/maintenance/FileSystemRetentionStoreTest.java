package com.iocextractor.adapter.out.maintenance;

import com.iocextractor.application.maintenance.RetentionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemRetentionStoreTest {

    @TempDir
    Path tempDir;

    private final FileSystemRetentionStore store = new FileSystemRetentionStore();

    @Test
    void lists_leaf_files_recursively_not_top_level_buckets() throws IOException {
        // partitions/<artifact>/<day>/<key>.csv — leaf files, not the artifact buckets
        Path leaf = tempDir.resolve("masks").resolve("2026-06-20").resolve("k1.csv");
        Files.createDirectories(leaf.getParent());
        Files.writeString(leaf, "id;mask\n1;a\n");

        List<RetentionEntry> entries = store.list(tempDir);

        assertThat(entries).extracting(e -> e.path().getFileName().toString()).containsExactly("k1.csv");
    }

    @Test
    void missing_directory_lists_empty() {
        assertThat(store.list(tempDir.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    void delete_removes_the_file() throws IOException {
        Path file = tempDir.resolve("done").resolve("a.htm");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");

        store.delete(new RetentionEntry(file, Files.getLastModifiedTime(file).toInstant()));

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void archive_moves_the_file_into_archive_dir() throws IOException {
        Path file = tempDir.resolve("failed").resolve("b.htm");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "y");
        Path archiveDir = tempDir.resolve("archive");

        store.archive(new RetentionEntry(file, Files.getLastModifiedTime(file).toInstant()), archiveDir);

        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.readString(archiveDir.resolve("b.htm"))).isEqualTo("y");
    }
}
