package com.iocextractor.application.dataframeimport.model;

import java.util.Locale;
import java.util.Objects;

/** SHA-256 fingerprint of all behavior-affecting settings of one import contract. */
public record ImportContractFingerprint(String value) {

    /** Validates and normalizes a lowercase 64-character hexadecimal digest. */
    public ImportContractFingerprint {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Import contract fingerprint must be a SHA-256 hex digest");
        }
    }
}
