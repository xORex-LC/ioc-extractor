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
        if (!isLowercaseSha256(keyHash)) {
            throw new IllegalArgumentException("Canonical key hash must be lower-case SHA-256");
        }
    }

    private static boolean isLowercaseSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
