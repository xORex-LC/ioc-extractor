package com.iocextractor.application.artifact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * Storage-neutral artifact row. Column order is preserved so CSV adapters can
 * round-trip records without leaking CSV details into the application service.
 */
public record ArtifactRow(Map<String, String> values) {

    public ArtifactRow {
        Objects.requireNonNull(values, "values");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Creates a row while preserving the supplied map iteration order.
     *
     * @param values row values by column name
     * @return artifact row
     */
    public static ArtifactRow ordered(Map<String, String> values) {
        return new ArtifactRow(new LinkedHashMap<>(values));
    }

    /**
     * Returns a column value or {@code null} when the column is absent.
     *
     * @param column column name
     * @return column value
     */
    public String value(String column) {
        return values.get(column);
    }

    /**
     * Returns a new row with one column replaced.
     *
     * @param column column name
     * @param value replacement value
     * @return updated row
     */
    public ArtifactRow withValue(String column, String value) {
        var copy = new LinkedHashMap<>(values);
        copy.put(column, value);
        return new ArtifactRow(copy);
    }
}
