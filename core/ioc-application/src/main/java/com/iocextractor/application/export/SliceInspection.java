package com.iocextractor.application.export;

import java.util.Objects;

/**
 * Storage-neutral filesystem inspection result consumed by export recovery.
 */
public record SliceInspection(String runId,
                              SliceInspectionState state,
                              String manifestSha256,
                              SliceManifest manifest,
                              String reason) {

    public SliceInspection {
        runId = ExportArtifactSpec.requireText(runId, "runId");
        state = Objects.requireNonNull(state, "state");
        boolean manifestExpected = state == SliceInspectionState.RECOVERABLE
                || state == SliceInspectionState.STAGED
                || state == SliceInspectionState.AVAILABLE;
        if (manifestExpected) {
            manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
            Objects.requireNonNull(manifest, "manifest");
        }
        boolean reasonExpected = state == SliceInspectionState.CORRUPT
                || state == SliceInspectionState.CONFLICT;
        if (reasonExpected && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Corrupt/conflicting slice inspection requires a reason");
        }
    }
}
