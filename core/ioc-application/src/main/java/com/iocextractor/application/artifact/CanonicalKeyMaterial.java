package com.iocextractor.application.artifact;

import java.util.Objects;

/** Collision-safe canonical key: indexed digest plus equality-check material. */
public record CanonicalKeyMaterial(String definitionId, String keyHash, String keyCanonical) {

    public CanonicalKeyMaterial {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("Canonical key definition id must not be blank");
        }
        Objects.requireNonNull(keyHash, "keyHash");
        Objects.requireNonNull(keyCanonical, "keyCanonical");
        if (!keyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Canonical key hash must be lower-case SHA-256");
        }
    }
}
