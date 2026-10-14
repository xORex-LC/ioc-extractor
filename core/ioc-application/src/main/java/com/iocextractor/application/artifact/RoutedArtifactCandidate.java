package com.iocextractor.application.artifact;

import java.util.Objects;

/** One side-effect-free document branch output, before final identity and reduction. */
public record RoutedArtifactCandidate(String artifact, PreparedArtifactRow row) {
    public RoutedArtifactCandidate {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(row, "row");
    }
}
