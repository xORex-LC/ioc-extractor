package com.iocextractor.application.dataframeimport.model;

import java.util.Locale;
import java.util.Objects;

/** SHA-256 digest used to pin immutable import snapshot or staging bytes. */
public record ImportSha256(String value) {

    /** Validates and normalizes a lowercase hexadecimal digest. */
    public ImportSha256 {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Import digest must be a SHA-256 hex value");
        }
    }
}
