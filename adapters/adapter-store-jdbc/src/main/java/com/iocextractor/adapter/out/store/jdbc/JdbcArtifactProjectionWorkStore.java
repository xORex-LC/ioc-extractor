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

/** SQLite-backed compare-and-set state for mutable artifact projection convergence. */
public final class JdbcArtifactProjectionWorkStore implements ArtifactProjectionWorkStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    /** Creates projection state access with an injected UTC timestamp source. */
    public JdbcArtifactProjectionWorkStore(DataSource dataSource, Clock clock) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        this.clock = Objects.requireNonNull(clock, "clock");
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
        long expected = acknowledgement.expectedRequiredGeneration().value();
        long installed = acknowledgement.installedGeneration().value();
        try {
            return jdbc.sql("""
                            UPDATE artifact_projection_state
                            SET projected_generation = :installed,
                                projected_at_ms = :projectedAt,
                                last_error_code = NULL
                            WHERE artifact = :artifact
                              AND required_generation = :expected
                              AND projected_generation <= :installed
                            """)
                    .param("installed", installed)
                    .param("projectedAt", clock.millis())
                    .param("artifact", artifact)
                    .param("expected", expected)
                    .update() == 1;
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
        try {
            return jdbc.sql("""
                            UPDATE artifact_projection_state
                            SET last_error_code = :failureCode
                            WHERE artifact = :artifact
                              AND required_generation = :expected
                              AND projected_generation < required_generation
                            """)
                    .param("failureCode", failureCode)
                    .param("artifact", artifact)
                    .param("expected", expectedGeneration.value())
                    .update() == 1;
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
