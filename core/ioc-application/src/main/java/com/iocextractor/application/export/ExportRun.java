package com.iocextractor.application.export;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable snapshot of one formation-only export run.
 *
 * <p>Paths are intentionally absent. Staging and final locations are derived by
 * filesystem adapters from the configured root, profile and immutable slice name.
 */
public record ExportRun(String runId,
                        String profile,
                        ExportRunStatus status,
                        String sliceName,
                        String planHash,
                        String manifestSha256,
                        Instant startedAt,
                        Instant updatedAt,
                        String reason) {

    public ExportRun {
        runId = ExportArtifactSpec.requireText(runId, "runId");
        profile = ExportArtifactSpec.requireText(profile, "profile");
        status = Objects.requireNonNull(status, "status");
        sliceName = requireSliceName(sliceName);
        planHash = ExportArtifactSpec.requireSha256(planHash, "planHash");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Export run updatedAt must not precede startedAt");
        }
        boolean manifestRequired = status == ExportRunStatus.STAGED
                || status == ExportRunStatus.AVAILABLE
                || status == ExportRunStatus.COMPLETED;
        if (manifestRequired) {
            manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
        } else if (manifestSha256 != null) {
            manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
        }
        if (status == ExportRunStatus.FAILED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A failed export run requires a reason");
        }
    }

    /** Creates the only valid initial state accepted by an export ledger. */
    public static ExportRun started(String runId,
                                    String profile,
                                    String sliceName,
                                    String planHash,
                                    Instant now) {
        return new ExportRun(runId, profile, ExportRunStatus.STARTED, sliceName, planHash,
                null, now, now, null);
    }

    private static String requireSliceName(String value) {
        String name = ExportArtifactSpec.requireText(value, "sliceName");
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Slice name must be a single relative path segment");
        }
        return name;
    }
}
