package com.iocextractor.application.export;

import com.iocextractor.application.artifact.FingerprintFraming;

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

    private static final String EXTERNAL_ID_COLUMN = "id";

    /** Version of the external-id mapping included in ID-bearing export plan fingerprints. */
    public static final String EXPORT_SLOT_POLICY_VERSION = "stable-sparse-reusable-v1";

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
            FingerprintFraming.add(digest, Integer.toString(manifestVersion));
            FingerprintFraming.add(digest, profile.name());
            FingerprintFraming.add(digest, profile.mode().name());
            FingerprintFraming.add(digest, format.type());
            FingerprintFraming.add(digest, format.charset());
            FingerprintFraming.add(digest, format.delimiter());
            FingerprintFraming.add(digest, format.quote());
            FingerprintFraming.add(digest, format.nullLiteral());
            if (artifacts.stream().anyMatch(artifact -> artifact.columns().contains(EXTERNAL_ID_COLUMN))) {
                FingerprintFraming.add(digest, EXPORT_SLOT_POLICY_VERSION);
            }
            for (ExportArtifactSpec artifact : artifacts) {
                FingerprintFraming.add(digest, artifact.artifactName());
                FingerprintFraming.add(digest, artifact.fileName());
                artifact.columns().forEach(column -> FingerprintFraming.add(digest, column));
                FingerprintFraming.add(digest, Integer.toString(artifact.identityEpoch()));
                FingerprintFraming.add(digest, artifact.identityHash());
                FingerprintFraming.add(digest, artifact.schemaHash());
                FingerprintFraming.add(digest, artifact.mappingHash());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}
