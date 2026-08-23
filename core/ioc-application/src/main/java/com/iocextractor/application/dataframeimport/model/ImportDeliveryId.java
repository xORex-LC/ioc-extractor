package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Non-reusable identity of one claimed file occurrence, independent of content digest. */
public record ImportDeliveryId(String value) {

    /** Enforces a non-blank delivery identity. */
    public ImportDeliveryId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Import delivery ID must not be blank");
        }
    }
}
