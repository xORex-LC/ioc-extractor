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
 * Archiving mirrors each entry's sub-path under the archive dir (via
 * {@link RetentionEntry#relativePath()}), so same-named leaves from different
 * sub-trees do not collide.
 *
 * <p>Because the unit is the leaf file, count-based retention pools all leaves under a
 * target — coarse for the nested partition tree (prefer age-based there); age-based
 * retention is unaffected. Per-group counting is deferred to the storage rework (ING-4).
 */
public final class FileSystemRetentionStore implements RetentionStore {

    @Override
    public List<RetentionEntry> list(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> toEntry(path, dir))
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
        // Mirror the entry's sub-path under archiveDir so leaf files that share a base
        // name across sub-trees (masks/<day>/<hash>.csv vs hashes/<day>/<hash>.csv) do
        // not overwrite each other; REPLACE_EXISTING then only re-archives the same item.
        Path target = archiveDir.resolve(entry.relativePath());
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                Files.move(entry.path(), target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(entry.path(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IocExtractorException("Failed to archive expired entry " + entry.path()
                    + " to " + target, e);
        }
    }

    private static RetentionEntry toEntry(Path path, Path baseDir) {
        try {
            Instant lastModified = Files.getLastModifiedTime(path).toInstant();
            return new RetentionEntry(path, lastModified, baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
