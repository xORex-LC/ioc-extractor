package com.iocextractor.adapter.out.sink.csv;

import com.iocextractor.application.aggregation.CanonicalArtifact;
import com.iocextractor.application.port.out.aggregation.CanonicalArtifactRepository;

import java.util.Objects;

/**
 * Canonical repository decorator that refreshes the CSV projection after a
 * successful canonical write.
 */
public final class ProjectingCanonicalArtifactRepository implements CanonicalArtifactRepository {

    private final CanonicalArtifactRepository delegate;
    private final CsvArtifactProjection projection;

    public ProjectingCanonicalArtifactRepository(CanonicalArtifactRepository delegate,
                                                 CsvArtifactProjection projection) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public CanonicalArtifact load(String artifactName) {
        return delegate.load(artifactName);
    }

    @Override
    public void write(String artifactName, CanonicalArtifact artifact) {
        delegate.write(artifactName, artifact);
        projection.project(artifactName);
    }
}
