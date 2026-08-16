package com.iocextractor.bootstrap;

import java.util.Objects;

/** Typed bootstrap value preventing processing and activation fingerprints from being mixed. */
record ProcessingPolicyIdentity(String value) {

    ProcessingPolicyIdentity {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Processing policy identity must not be blank");
        }
    }
}
