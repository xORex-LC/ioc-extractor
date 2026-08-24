package com.iocextractor.application.dataframeimport.model;

import com.iocextractor.application.maintenance.RetentionAction;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Declarative retention policy for a disjoint set of managed-import terminal outcomes.
 * The vocabulary intentionally matches the common housekeeping policy.
 */
public record ImportTerminalRetentionTarget(
        String name,
        Set<ImportTerminalOutcome> outcomes,
        Duration maxAge,
        int maxCount,
        RetentionAction action,
        Path archiveDirectory) {

    /** Enforces a useful age/count policy and archive destination contract. */
    public ImportTerminalRetentionTarget {
        Objects.requireNonNull(name, "name");
        outcomes = Set.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        Objects.requireNonNull(action, "action");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Import retention target name must not be blank");
        }
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("Import retention target outcomes must not be empty");
        }
        if (maxCount < 0) {
            throw new IllegalArgumentException("Import retention max count must not be negative");
        }
        boolean ageEnabled = maxAge != null && maxAge.isPositive();
        if (!ageEnabled && maxCount == 0) {
            throw new IllegalArgumentException("Import retention target must enable max age or max count");
        }
        if (maxAge != null && maxAge.isNegative()) {
            throw new IllegalArgumentException("Import retention max age must not be negative");
        }
        if (action == RetentionAction.ARCHIVE) {
            Objects.requireNonNull(archiveDirectory,
                    "archiveDirectory is required for ARCHIVE import retention target");
        }
    }
}
