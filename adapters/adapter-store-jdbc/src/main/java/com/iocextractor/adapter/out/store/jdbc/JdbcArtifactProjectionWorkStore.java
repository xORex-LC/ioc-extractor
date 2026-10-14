package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ArtifactProjectionState;
import com.iocextractor.application.artifact.lifecycle.ProjectionAcknowledgement;
import com.iocextractor.application.artifact.lifecycle.ProjectionGeneration;
import com.iocextractor.application.port.out.artifact.lifecycle.ArtifactProjectionWorkStore;
import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Objects;

/** SQLite-backed monotonic coverage state for mutable artifact projection convergence. */
public final class JdbcArtifactProjectionWorkStore implements ArtifactProjectionWorkStore {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JdbcWriterAdmission writerAdmission;
    private final JdbcProjectionMutation mutation;

    /** Creates projection state access with an injected UTC timestamp source. */
    public JdbcArtifactProjectionWorkStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, new JdbcWriterAdmission());
    }

    /** Creates projection state access participating in shared local write admission. */
    public JdbcArtifactProjectionWorkStore(DataSource dataSource,
                                           Clock clock,
                                           JdbcWriterAdmission writerAdmission) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        this.mutation = new JdbcProjectionMutation(jdbc);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writerAdmission = Objects.requireNonNull(writerAdmission, "writerAdmission");
    }

    @Override
    public ArtifactProjectionState load(String artifactName) {
        String artifact = DataframeColumn.requireSqlIdentifier(artifactName, "artifact name");
        try {
            return jdbc.sql("""
                            SELECT required_generation, projected_generation
                            FROM artifact_projection_state
                            WHERE artifact = :artifact
                            """)
                    .param("artifact", artifact)
                    .query((resultSet, rowNumber) -> new ArtifactProjectionState(
                            artifact,
                            new ProjectionGeneration(resultSet.getLong("required_generation")),
                            new ProjectionGeneration(resultSet.getLong("projected_generation"))))
                    .optional()
                    .orElseGet(() -> zeroState(artifact));
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to load projection work for artifact: " + artifact, e);
        }
    }

    @Override
    public boolean acknowledge(ProjectionAcknowledgement acknowledgement) {
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        String artifact = DataframeColumn.requireSqlIdentifier(
                acknowledgement.artifactName(), "artifact name");
        long installed = acknowledgement.installedGeneration().value();
        return writerAdmission.execute(JdbcWriterAdmission.OperationClass.CONTROL, () -> acknowledgeAdmitted(artifact, installed));
    }

    private boolean acknowledgeAdmitted(String artifact, long installed) {
        try {
            return mutation.acknowledge(artifact, installed, clock.millis());
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to acknowledge projection work for artifact: " + artifact, e);
        }
    }

    @Override
    public boolean recordFailure(String artifactName,
                                 ProjectionGeneration expectedGeneration,
                                 String failureCode) {
        String artifact = DataframeColumn.requireSqlIdentifier(artifactName, "artifact name");
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
        return writerAdmission.execute(JdbcWriterAdmission.OperationClass.CONTROL, () -> recordFailureAdmitted(
                artifact, expectedGeneration, failureCode));
    }

    private boolean recordFailureAdmitted(String artifact,
                                          ProjectionGeneration expectedGeneration,
                                          String failureCode) {
        try {
            return mutation.recordFailure(artifact, expectedGeneration.value(), failureCode);
        } catch (RuntimeException e) {
            throw new IocExtractorException(
                    "Failed to record projection failure for artifact: " + artifact, e);
        }
    }

    private ArtifactProjectionState zeroState(String artifact) {
        var zero = new ProjectionGeneration(0);
        return new ArtifactProjectionState(artifact, zero, zero);
    }
}
