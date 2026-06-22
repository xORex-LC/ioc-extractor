package com.iocextractor.adapter.out.maintenance;

import com.iocextractor.application.maintenance.RetentionEntry;
import com.iocextractor.application.port.out.maintenance.RetentionStore;
import com.iocextractor.common.IocExtractorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Filesystem {@link RetentionStore}. It reaps <em>leaf files</em> recursively, so
 * it works uniformly for flat archive dirs ({@code done}/{@code failed}) and for
 * the nested partition tree ({@code partitions/<artifact>/<day>/<key>.csv}) without
 * mistaking the permanent top-level buckets for reapable units. Empty directories
 * left behind are harmless and intentionally not pruned (keeps reaping path-safe).
 */
public final class FileSystemRetentionStore implements RetentionStore {

    @Override
    public List<RetentionEntry> list(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(FileSystemRetentionStore::toEntry)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new IocExtractorException("Failed to scan retention directory: " + dir, e);
        }
    }

    @Override
    public void delete(RetentionEntry entry) {
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException e) {
            throw new IocExtractorException("Failed to delete expired entry: " + entry.path(), e);
        }
    }

    @Override
    public void archive(RetentionEntry entry, Path archiveDir) {
        Path target = archiveDir.resolve(entry.path().getFileName());
        try {
            Files.createDirectories(archiveDir);
            try {
                Files.move(entry.path(), target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(entry.path(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to archive expired entry " + entry.path()
                    + " to " + archiveDir, e);
        }
    }

    private static RetentionEntry toEntry(Path path) {
        try {
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            return new RetentionEntry(path, lastModified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
