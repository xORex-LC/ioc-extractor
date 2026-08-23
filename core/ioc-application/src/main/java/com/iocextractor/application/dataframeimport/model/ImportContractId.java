package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Stable operator-defined identity of one versioned dataframe import contract. */
public record ImportContractId(String value) {

    /** Enforces a non-blank identity. */
    public ImportContractId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Import contract ID must not be blank");
        }
    }
}
