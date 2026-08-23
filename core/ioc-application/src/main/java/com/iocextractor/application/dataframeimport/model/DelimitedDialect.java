package com.iocextractor.application.dataframeimport.model;

import java.util.List;
import java.util.Objects;

/**
 * Validated library-neutral delimiter grammar pinned by an import contract.
 * Concrete parser options are created only by the CSV adapter.
 */
public record DelimitedDialect(
        char delimiter,
        char quote,
        ImportRecordSeparator recordSeparator,
        boolean headerRequired,
        List<String> nullLiterals) {

    /** Enforces parser-independent dialect invariants. */
    public DelimitedDialect {
        if (delimiter == quote) {
            throw new IllegalArgumentException("Delimited input quote must differ from delimiter");
        }
        Objects.requireNonNull(recordSeparator, "recordSeparator");
        if (!headerRequired) {
            throw new IllegalArgumentException("Delimited input header is required in V1");
        }
        nullLiterals = List.copyOf(Objects.requireNonNull(nullLiterals, "nullLiterals"));
    }
}
