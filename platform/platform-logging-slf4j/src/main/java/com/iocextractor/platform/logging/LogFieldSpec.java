package com.iocextractor.platform.logging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares the wire name and scalar representation of one structured log field.
 *
 * <p>Keep these descriptors in the service's field catalog. Keys are case-sensitive
 * dotted paths: every segment starts with an ASCII letter or underscore, followed
 * by letters, digits, underscores or hyphens. No trimming or case conversion occurs.
 * A valid path can still collide with encoder metadata or another path's parent;
 * the service must check its complete catalog for those conflicts.
 *
 * <p>Record equality includes both components. Within a {@link LogEvent}, the key
 * identifies a field and a conflicting type is rejected, even after null omission.
 *
 * @param key dotted wire path, for example {@code collector.task.id}
 * @param valueType scalar representation expected by the log consumer
 */
public record LogFieldSpec(String key, LogValueType valueType) {
    // Possessive repetition keeps long dotted keys from exhausting the regex call stack.
    private static final Pattern KEY = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_-]*+(?:\\.[A-Za-z_][A-Za-z0-9_-]*+)*+");

    /**
     * Creates a field declaration without registering it in any global catalog.
     *
     * @param key dotted field path
     * @param valueType declared scalar representation
     * @throws NullPointerException if either component is null
     * @throws IllegalArgumentException if the key does not follow the path grammar
     */
    public LogFieldSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valueType, "valueType");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid structured log field key: " + key);
        }
    }
}
