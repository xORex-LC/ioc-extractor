package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Value-free diagnostic for an accepted logical row; artifact may be absent. */
public record ImportRowWarning(long sourceRowNumber, String artifact, String code) {
    public ImportRowWarning {
        if (sourceRowNumber < 1 || Objects.requireNonNull(code, "code").isBlank()) {
            throw new IllegalArgumentException("Import warning requires a row number and code");
        }
    }
}
