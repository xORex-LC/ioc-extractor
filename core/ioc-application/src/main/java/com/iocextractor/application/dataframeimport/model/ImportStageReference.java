package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Opaque application reference to a closed, integrity-pinned disk staging unit. */
public record ImportStageReference(String value) {

    /** Enforces a non-blank adapter-owned reference. */
    public ImportStageReference {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Import stage reference must not be blank");
        }
    }
}
