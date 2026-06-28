package com.iocextractor.application.port.in.export;

import com.iocextractor.application.export.ExportRunStatus;

import java.util.Objects;

/** Outcome of one requested export profile run. */
public record ExportArtifactsResult(String runId,
                                    String profile,
                                    ExportRunStatus status,
                                    String sliceName) {

    public ExportArtifactsResult {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
        if (runId == null && status != ExportRunStatus.SKIPPED) {
            throw new IllegalArgumentException("Only a pre-gate SKIPPED result may omit runId");
        }
    }

    /** Returns a no-op result when the cheap revision/plan gate avoided creating a run. */
    public static ExportArtifactsResult unchanged(String profile) {
        return new ExportArtifactsResult(null, profile, ExportRunStatus.SKIPPED, null);
    }
}
