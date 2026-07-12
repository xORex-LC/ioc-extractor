package com.iocextractor.application.ingest;

import com.iocextractor.application.port.out.artifact.ArtifactPreparer;

import java.util.List;
import java.util.Objects;

/** Source-scoped set of side-effect-free artifact preparers. */
public record SourcePreparers(List<ArtifactPreparer> preparers) {

    public SourcePreparers {
        preparers = List.copyOf(Objects.requireNonNull(preparers, "preparers"));
    }

    /** Returns configured artifact names in preparation order. */
    public List<String> artifactNames() {
        return preparers.stream().map(ArtifactPreparer::name).toList();
    }
}
