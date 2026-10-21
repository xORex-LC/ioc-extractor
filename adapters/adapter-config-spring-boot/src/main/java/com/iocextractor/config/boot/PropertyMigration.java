package com.iocextractor.config.boot;

import java.util.Objects;
import java.util.Optional;

/**
 * Service-owned description of an old property name. This neither registers a Binder
 * alias nor converts a value. Keep any compatibility deadline and enforcement in the service.
 *
 * @param path canonical old path, using {@code []} for each numeric list index
 * @param kind whether the service still accepts the old name
 * @param replacement optional canonical replacement with the same number of list indexes
 */
public record PropertyMigration(String path, Kind kind, Optional<String> replacement) {

    /** Rejects invalid templates before inspecting declarations. */
    public PropertyMigration {
        MigrationPath oldPath = new MigrationPath(path);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(replacement, "replacement");
        replacement.ifPresent(value -> oldPath.validateReplacement(new MigrationPath(value)));
    }

    /** Acceptance is descriptive metadata; the library never decides whether startup may continue. */
    public enum Kind {
        /** The service still binds this name during a compatibility window. */
        DEPRECATED,
        /** The name has been removed and need not appear in the current record schema. */
        REMOVED
    }
}
