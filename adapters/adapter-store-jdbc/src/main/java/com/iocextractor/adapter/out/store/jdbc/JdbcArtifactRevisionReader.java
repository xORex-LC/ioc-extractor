package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.export.ArtifactRevision;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC read side for cheap per-artifact canonical change detection.
 *
 * <p>Missing rows represent artifacts that have never accepted a public row
 * and are returned as revision zero without a change timestamp.
 */
public final class JdbcArtifactRevisionReader implements ArtifactRevisionReader {

    private final DataSource dataSource;

    public JdbcArtifactRevisionReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<ArtifactRevision> read(List<String> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT revision, changed_at
                     FROM artifact_revision
                     WHERE artifact = ?
                     """)) {
            List<ArtifactRevision> revisions = new ArrayList<>(artifacts.size());
            for (String artifact : artifacts) {
                if (artifact == null || artifact.isBlank()) {
                    throw new IllegalArgumentException("Artifact name must not be blank");
                }
                statement.setString(1, artifact);
                try (var resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        String changedAt = resultSet.getString("changed_at");
                        revisions.add(new ArtifactRevision(
                                artifact,
                                resultSet.getLong("revision"),
                                changedAt == null ? null : Instant.parse(changedAt)));
                    } else {
                        revisions.add(new ArtifactRevision(artifact, 0, null));
                    }
                }
            }
            return List.copyOf(revisions);
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to read artifact revisions", e);
        }
    }
}
