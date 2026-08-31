package com.iocextractor.application.export;

import com.iocextractor.application.artifact.FingerprintFraming;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Stable fingerprint of ordered public column names and their declared storage-neutral types. */
public final class ArtifactSchemaFingerprint {

    private ArtifactSchemaFingerprint() {
    }

    /**
     * Hashes an ordered public schema. Blank declared types normalize to {@code TEXT}.
     *
     * @param columns ordered public column names
     * @param declaredTypes aligned declared type names
     * @return lower-case SHA-256 fingerprint
     */
    public static String sha256(List<String> columns, List<String> declaredTypes) {
        List<String> names = List.copyOf(Objects.requireNonNull(columns, "columns"));
        List<String> types = new ArrayList<>(Objects.requireNonNull(declaredTypes, "declaredTypes"));
        if (names.isEmpty() || names.size() != types.size()) {
            throw new IllegalArgumentException("Schema columns and declared types must be non-empty and aligned");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FingerprintFraming.add(digest, "schema:v1");
            for (int index = 0; index < names.size(); index++) {
                String name = Objects.requireNonNull(names.get(index), "column");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Schema column must not be blank");
                }
                String sourceType = types.get(index);
                String type = sourceType == null || sourceType.isBlank()
                        ? "TEXT" : sourceType.trim().toUpperCase(Locale.ROOT);
                FingerprintFraming.add(digest, name);
                FingerprintFraming.add(digest, type);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}
