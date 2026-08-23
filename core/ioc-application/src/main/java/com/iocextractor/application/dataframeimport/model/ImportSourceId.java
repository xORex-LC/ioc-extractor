package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Stable operator-defined identity and trust boundary of an import source. */
public record ImportSourceId(String value) {

    /** Enforces a non-blank identity. */
    public ImportSourceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Import source ID must not be blank");
        }
    }
}
