package com.iocextractor.application.export;

import java.time.Instant;
import java.util.Objects;

/** Completed local slice considered as one indivisible retention unit. */
public record SliceDescriptor(String sliceId,
                              String profile,
                              String sliceName,
                              Instant createdAt) {

    public SliceDescriptor {
        sliceId = ExportArtifactSpec.requireText(sliceId, "sliceId");
        profile = ExportArtifactSpec.requireText(profile, "profile");
        sliceName = ExportArtifactSpec.requireText(sliceName, "sliceName");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
