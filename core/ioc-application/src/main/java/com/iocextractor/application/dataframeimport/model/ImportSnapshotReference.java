package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Opaque application reference to a private immutable source snapshot. */
public record ImportSnapshotReference(String value) {

    /** Enforces a non-blank adapter-owned reference. */
    public ImportSnapshotReference {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Import snapshot reference must not be blank");
        }
    }
}
