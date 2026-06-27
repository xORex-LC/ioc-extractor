package com.iocextractor.application.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Fully resolved, ordered export contract used to derive deterministic slice bytes.
 *
 * <p>The plan hash covers every setting that can change emitted bytes or their
 * interpretation. Runtime identifiers and timestamps are intentionally excluded.
 */
public record ExportPlan(int manifestVersion,
                         ExportProfile profile,
                         ExportFormat format,
                         List<ExportArtifactSpec> artifacts) {

    public ExportPlan {
        if (manifestVersion < 1) {
            throw new IllegalArgumentException("Manifest version must be positive");
        }
        profile = Objects.requireNonNull(profile, "profile");
        format = Objects.requireNonNull(format, "format");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        List<String> plannedNames = artifacts.stream().map(ExportArtifactSpec::artifactName).toList();
        if (!profile.artifacts().equals(plannedNames)) {
            throw new IllegalArgumentException("Export plan artifacts must match profile order exactly");
        }
    }

    /**
     * Returns a stable SHA-256 fingerprint of the byte-affecting plan fields.
     */
    public String planHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, Integer.toString(manifestVersion));
            add(digest, profile.name());
            add(digest, profile.mode().name());
            add(digest, format.type());
            add(digest, format.charset());
            add(digest, format.delimiter());
            add(digest, format.quote());
            add(digest, format.nullLiteral());
            for (ExportArtifactSpec artifact : artifacts) {
                add(digest, artifact.artifactName());
                add(digest, artifact.fileName());
                artifact.columns().forEach(column -> add(digest, column));
                add(digest, Integer.toString(artifact.identityEpoch()));
                add(digest, artifact.identityHash());
                add(digest, artifact.schemaHash());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }
}
