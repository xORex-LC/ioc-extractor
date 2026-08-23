package com.iocextractor.application.dataframeimport.model;

import java.util.Locale;
import java.util.Objects;

/** SHA-256 fingerprint of one complete compiled dataframe-import catalog generation. */
public record ImportCatalogFingerprint(String value) {

    /** Validates and normalizes a lowercase 64-character hexadecimal digest. */
    public ImportCatalogFingerprint {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Import catalog fingerprint must be a SHA-256 hex digest");
        }
    }
}
