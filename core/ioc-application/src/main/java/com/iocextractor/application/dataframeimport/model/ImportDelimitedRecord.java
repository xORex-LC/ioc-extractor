package com.iocextractor.application.dataframeimport.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Strictly decoded CSV record before declarative mapping.
 *
 * @param sourceRowNumber one-based parser record number
 * @param values canonical recognized header to exact decoded value
 */
public record ImportDelimitedRecord(long sourceRowNumber, Map<String, String> values) {

    /** Snapshots record values and enforces a positive row number. */
    public ImportDelimitedRecord {
        if (sourceRowNumber < 1) {
            throw new IllegalArgumentException("Delimited import row number must be positive");
        }
        values = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }
}
