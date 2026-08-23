package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleHistoryStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Indexed, bounded SQLite retention for typed lifecycle history. */
public final class JdbcLifecycleHistoryStore implements LifecycleHistoryStore {

    private final DataSource dataSource;
    private final Set<String> artifacts;

    public JdbcLifecycleHistoryStore(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(schemas, "schemas");
        Set<String> names = new LinkedHashSet<>();
        for (DataframeArtifactSchema schema : schemas) {
            String name = Objects.requireNonNull(schema, "schema").artifactName();
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate dataframe artifact schema: " + name);
            }
        }
        this.artifacts = Set.copyOf(names);
    }

    @Override
    public HistoryPurgeResult purge(String artifactName, EffectiveTime cutoff, int batchSize) {
        String artifact = requireArtifact(artifactName);
        Objects.requireNonNull(cutoff, "cutoff");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("History retention batch size must be positive");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                int purged = deleteBatch(connection, artifact, cutoff, batchSize);
                boolean moreEligible = hasEligible(connection, artifact, cutoff);
                connection.commit();
                return new HistoryPurgeResult(artifact, purged, moreEligible);
            } catch (SQLException | RuntimeException e) {
                failure = e;
                JdbcLifecycleTransactions.rollback(connection, e);
                throw e;
            } finally {
                JdbcLifecycleTransactions.restoreAutoCommit(connection, previousAutoCommit, failure);
            }
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to purge lifecycle history: " + artifact, e);
        }
    }

    private int deleteBatch(Connection connection,
                            String artifact,
                            EffectiveTime cutoff,
                            int batchSize) throws SQLException {
        String history = quote(artifact + "_history");
        String sql = "DELETE FROM " + history + " WHERE " + quote("history_id") + " IN ("
                + "SELECT " + quote("history_id") + " FROM " + history
                + " WHERE " + quote("closed_at_epoch_ms") + " <= ?"
                + " ORDER BY " + quote("closed_at_epoch_ms") + ", " + quote("history_id")
                + " LIMIT ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cutoff.value().toEpochMilli());
            statement.setInt(2, batchSize);
            return statement.executeUpdate();
        }
    }

    private boolean hasEligible(Connection connection,
                                String artifact,
                                EffectiveTime cutoff) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact + "_history")
                + " WHERE " + quote("closed_at_epoch_ms") + " <= ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, cutoff.value().toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String requireArtifact(String artifactName) {
        String artifact = DataframeColumn.requireSqlIdentifier(artifactName, "artifact name");
        if (!artifacts.contains(artifact)) {
            throw new IocExtractorException("Unknown dataframe artifact: " + artifact);
        }
        return artifact;
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
