package com.iocextractor.application.export;

import java.util.Objects;

/** Verified durable staging result, not yet visible in the published slice area. */
public record StagedSlice(String sliceId,
                          String sliceName,
                          String manifestSha256,
                          SliceManifest manifest) {

    public StagedSlice {
        sliceId = ExportArtifactSpec.requireText(sliceId, "sliceId");
        sliceName = ExportArtifactSpec.requireText(sliceName, "sliceName");
        manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (!sliceId.equals(manifest.sliceId())) {
            throw new IllegalArgumentException("Staged slice id must match manifest");
        }
    }
}
