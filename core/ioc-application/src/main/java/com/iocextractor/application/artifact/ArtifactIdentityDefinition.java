package com.iocextractor.application.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Storage-neutral artifact identity policy derived from configuration.
 */
public record ArtifactIdentityDefinition(String artifactName,
                                         CanonicalKeyDefinition recordKey,
                                         List<CanonicalKeyDefinition> matchKeys,
                                         int epoch) {

    /** Compatibility constructor for one unnamed legacy record-key formula. */
    public ArtifactIdentityDefinition(String artifactName,
                                      List<String> columns,
                                      boolean firstNonEmpty,
                                      int epoch) {
        this(artifactName,
                new CanonicalKeyDefinition(
                        artifactName + "-row-v" + epoch,
                        firstNonEmpty ? CanonicalKeyMode.FIRST_NON_EMPTY : CanonicalKeyMode.COMPOSITE,
                        columns),
                List.of(),
                epoch);
    }

    public ArtifactIdentityDefinition {
        Objects.requireNonNull(artifactName, "artifactName");
        Objects.requireNonNull(recordKey, "recordKey");
        matchKeys = List.copyOf(Objects.requireNonNull(matchKeys, "matchKeys"));
        if (artifactName.isBlank()) {
            throw new IllegalArgumentException("Artifact name must not be blank");
        }
        if (epoch < 1) {
            throw new IllegalArgumentException("Artifact identity epoch must be positive");
        }
        if (matchKeys.stream().map(CanonicalKeyDefinition::definitionId).distinct().count() != matchKeys.size()) {
            throw new IllegalArgumentException("Artifact match-key definition ids must be unique");
        }
    }

    /** Returns the record-key columns retained by the established API. */
    public List<String> columns() {
        return recordKey.columns();
    }

    /** Returns whether the record formula retains legacy first-non-empty semantics. */
    public boolean firstNonEmpty() {
        return recordKey.mode() == CanonicalKeyMode.FIRST_NON_EMPTY;
    }

    /**
     * Hash of the identity formula, not of data rows. Stored per artifact to
     * detect drift in key columns or normalization semantics.
     */
    public String identityHash() {
        return sha256(canonicalDescriptorJson());
    }

    private String canonicalDescriptorJson() {
        StringBuilder json = new StringBuilder();
        json.append("[\"normalizer:v1\",");
        json.append(firstNonEmpty() ? "\"first-non-empty\"" : "\"composite\"");
        for (String column : columns()) {
            json.append(',').append(CanonicalArtifactIdentityResolver.jsonString(column));
        }
        json.append(']');
        return json.toString();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
