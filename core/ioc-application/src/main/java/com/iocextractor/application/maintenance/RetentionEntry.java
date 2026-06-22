package com.iocextractor.application.maintenance;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A single reapable item discovered under a {@link RetentionTarget}: its path and
 * last-modified time. The store produces these; the {@link RetentionPolicy} ranks
 * and selects among them.
 *
 * @param path         filesystem path of the item
 * @param lastModified its last-modified timestamp (used for age and ordering)
 */
public record RetentionEntry(Path path, Instant lastModified) {

    public RetentionEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(lastModified, "lastModified");
    }
}
