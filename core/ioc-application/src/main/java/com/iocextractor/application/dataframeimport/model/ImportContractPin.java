package com.iocextractor.application.dataframeimport.model;

import java.util.Objects;

/** Immutable contract identity pinned after exact-one delivery recognition. */
public record ImportContractPin(
        ImportContractId id,
        int version,
        ImportContractFingerprint fingerprint) {

    /** Enforces a complete, versioned contract identity. */
    public ImportContractPin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (version < 1) {
            throw new IllegalArgumentException("Pinned import contract version must be positive");
        }
    }
}
