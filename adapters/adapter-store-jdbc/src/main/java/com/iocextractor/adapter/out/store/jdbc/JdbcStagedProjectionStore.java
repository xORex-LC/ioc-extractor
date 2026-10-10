package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import java.util.Set;

/** Existing projection compare-and-set operations over committed version metadata. */
final class JdbcStagedProjectionStore implements ArtifactProjectionWorkStore {
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcArtifactProjectionWorkStore reader;
    JdbcStagedProjectionStore(JdbcStagedCanonicalBackend backend) {
        this.backend = backend;
        reader = new JdbcArtifactProjectionWorkStore(backend.configuration().dataSource(), backend.configuration().allocatorClock());
    }
    public ArtifactProjectionState load(String artifact) { backend.requireArtifact(artifact); return reader.load(artifact); }
    public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
        String artifact = acknowledgement.artifactName(); backend.requireArtifact(artifact);
        return new JdbcStagedControl(backend).execute(Set.of("artifact:" + artifact), JdbcWriterAdmission.OperationClass.CONTROL,
                (selection, base, slots) -> base.metadata(artifact),
                (working, context, asOf) -> new JdbcArtifactProjectionWorkStore(working, backend.configuration().allocatorClock()).acknowledge(acknowledgement));
    }
    public boolean recordFailure(String artifact, ProjectionGeneration expected, String code) {
        backend.requireArtifact(artifact);
        return new JdbcStagedControl(backend).execute(Set.of("artifact:" + artifact), JdbcWriterAdmission.OperationClass.CONTROL,
                (selection, base, slots) -> base.metadata(artifact),
                (working, context, asOf) -> new JdbcArtifactProjectionWorkStore(working, backend.configuration().allocatorClock()).recordFailure(artifact, expected, code));
    }
}
