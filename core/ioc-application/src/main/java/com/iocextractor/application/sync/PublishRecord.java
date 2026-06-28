package com.iocextractor.application.sync;

import java.time.Instant;
import java.util.Objects;

/** Durable publish-ledger row for one immutable slice and one configured target. */
public record PublishRecord(String sliceId,
                            String targetId,
                            String profile,
                            String sliceName,
                            String manifestSha256,
                            String endpoint,
                            String remotePath,
                            PublishStatus status,
                            int attempts,
                            String lastError,
                            String remoteVerification,
                            Instant createdAt,
                            Instant updatedAt) {

    public PublishRecord {
        sliceId = requireText(sliceId, "sliceId");
        targetId = requireText(targetId, "targetId");
        profile = requireText(profile, "profile");
        sliceName = requireText(sliceName, "sliceName");
        manifestSha256 = requireSha256(manifestSha256, "manifestSha256");
        endpoint = requireText(endpoint, "endpoint");
        remotePath = requireText(remotePath, "remotePath");
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Creates a new pending pair for reconcile insertion. */
    public static PublishRecord pending(String sliceId,
                                        String targetId,
                                        String profile,
                                        String sliceName,
                                        String manifestSha256,
                                        String endpoint,
                                        String remotePath,
                                        Instant now) {
        return new PublishRecord(sliceId, targetId, profile, sliceName, manifestSha256,
                endpoint, remotePath, PublishStatus.PENDING, 0, null, null, now, now);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSha256(String value, String name) {
        value = requireText(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex value");
        }
        return value;
    }
}
