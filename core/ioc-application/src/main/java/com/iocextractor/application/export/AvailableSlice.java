package com.iocextractor.application.export;

import java.util.Objects;

/** Immutable slice atomically visible to local delivery consumers. */
public record AvailableSlice(String sliceId,
                             String sliceName,
                             String manifestSha256,
                             SliceManifest manifest) {

    public AvailableSlice {
        sliceId = ExportArtifactSpec.requireText(sliceId, "sliceId");
        sliceName = ExportArtifactSpec.requireText(sliceName, "sliceName");
        manifestSha256 = ExportArtifactSpec.requireSha256(manifestSha256, "manifestSha256");
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (!sliceId.equals(manifest.sliceId())) {
            throw new IllegalArgumentException("Available slice id must match manifest");
        }
    }
}
