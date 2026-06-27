package com.iocextractor.application.port.in.export;

import com.iocextractor.application.export.ExportRunStatus;

import java.util.Objects;

/** Outcome of one requested export profile run. */
public record ExportArtifactsResult(String runId,
                                    String profile,
                                    ExportRunStatus status,
                                    String sliceName) {

    public ExportArtifactsResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(status, "status");
    }
}
