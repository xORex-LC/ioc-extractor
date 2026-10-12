package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Isolated ACK/error experiment; committed typed visibility and the existing CAS policy stay authoritative. */
final class JdbcDirectProjectionStore implements ArtifactProjectionWorkStore {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcArtifactProjectionWorkStore reader;

    JdbcDirectProjectionStore(JdbcStagedCanonicalBackend backend) {
        this.backend = backend;
        reader = new JdbcArtifactProjectionWorkStore(backend.configuration().dataSource(), backend.configuration().allocatorClock());
    }

    @Override public ArtifactProjectionState load(String artifact) {
        backend.requireArtifact(artifact);
        return reader.load(artifact);
    }

    @Override public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        String artifact = acknowledgement.artifactName();
        return backend.publication().updateProjection(unit(artifact), artifact, mutation -> mutation.acknowledge(
                artifact, acknowledgement.installedGeneration().value(), backend.configuration().allocatorClock().millis()));
    }

    @Override public boolean recordFailure(String artifact, ProjectionGeneration expected, String code) {
        Objects.requireNonNull(expected, "expected");
        if (code == null || code.isBlank()) { throw new IllegalArgumentException("failureCode must not be blank"); }
        // Conservative UTF-8 bound without allocating an unbounded encoding before admission.
        if (3L * code.length() > backend.configuration().workspaces().canonicalChunkBytes() - 2048L) {
            throw new IllegalArgumentException("Projection failure code exceeds direct byte admission");
        }
        return backend.publication().updateProjection(unit(artifact), artifact,
                mutation -> mutation.recordFailure(artifact, expected.value(), code));
    }

    private JdbcStagedPublication.Unit unit(String artifact) {
        backend.requireArtifact(artifact);
        return new JdbcStagedPublication.Unit("control:" + UUID.randomUUID(), backend.configuration().fingerprint(),
                Set.of("artifact:" + artifact), false);
    }
}
