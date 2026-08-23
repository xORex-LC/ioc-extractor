package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/**
 * Safe row-level issue without raw imported values.
 *
 * @param sourceRowNumber one-based source row number
 * @param artifact optional artifact label
 * @param code stable diagnostic code
 */
public record ImportRowIssue(long sourceRowNumber, String artifact, String code) {

    /** Enforces a positive row and stable non-blank code. */
    public ImportRowIssue {
        Objects.requireNonNull(code, "code");
        if (sourceRowNumber < 1 || code.isBlank()) {
            throw new IllegalArgumentException("Import row issue requires positive row and non-blank code");
        }
    }
}
