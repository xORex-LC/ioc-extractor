package com.iocextractor.application.maintenance;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * A declaratively configured directory the reaper maintains (e.g. {@code partitions},
 * {@code done}, {@code failed}). The same policy serves all of them; only these
 * parameters differ.
 *
 * @param name       human-readable target name (for logging/metrics)
 * @param dir        directory whose top-level entries are subject to retention
 * @param maxAge     entries older than this are expired; {@code null}/zero disables age-based reaping
 * @param maxCount   keep at most this many newest entries, counted over the leaf entries the
 *                   store lists; for a nested tree (e.g. {@code partitions}) this pools all
 *                   leaves together, so prefer {@code maxAge} there. {@code <= 0} disables count-based reaping
 * @param action     what to do with expired entries
 * @param archiveDir destination for {@link RetentionAction#ARCHIVE}; ignored for {@code DELETE}
 */
public record RetentionTarget(String name,
                              Path dir,
                              Duration maxAge,
                              int maxCount,
                              RetentionAction action,
                              Path archiveDir) {

    public RetentionTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(action, "action");
        if (action == RetentionAction.ARCHIVE) {
            Objects.requireNonNull(archiveDir, "archiveDir is required for ARCHIVE target " + name);
        }
    }
}
