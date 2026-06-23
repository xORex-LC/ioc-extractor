package com.iocextractor.application.maintenance;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A single reapable item discovered under a {@link RetentionTarget}: its path, its
 * last-modified time, and the target root it was discovered under. The store produces
 * these; the {@link RetentionPolicy} ranks and selects among them (using only
 * {@code lastModified}). {@code baseDir} lets an archiving store preserve the item's
 * sub-path instead of flattening to the bare file name.
 *
 * @param path         filesystem path of the item
 * @param lastModified its last-modified timestamp (used for age and ordering)
 * @param baseDir      the target root {@code path} was discovered under; basis for
 *                     {@link #relativePath()} so archiving stays collision-free
 */
public record RetentionEntry(Path path, Instant lastModified, Path baseDir) {

    public RetentionEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lastModified, "lastModified");
        Objects.requireNonNull(baseDir, "baseDir");
    }

    /**
     * The item's path relative to its {@link #baseDir} (e.g. {@code masks/2026-06-20/k1.csv}),
     * falling back to the bare file name when {@code path} is not under {@code baseDir}.
     * An archiving store mirrors this under the archive dir, so leaf files that share a
     * base name across sub-trees (e.g. {@code masks/<day>/<hash>.csv} and
     * {@code hashes/<day>/<hash>.csv}) do not overwrite each other.
     */
    public Path relativePath() {
        if (!path.startsWith(baseDir)) {
            return path.getFileName();
        }
        Path relative = baseDir.relativize(path);
        return relative.toString().isEmpty() ? path.getFileName() : relative;
    }
}
