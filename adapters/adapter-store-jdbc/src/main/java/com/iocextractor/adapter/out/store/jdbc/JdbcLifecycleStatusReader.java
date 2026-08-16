package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleActivationState;
import com.iocextractor.application.artifact.lifecycle.LifecycleArtifactStatistics;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockSnapshot;
import com.iocextractor.application.artifact.lifecycle.LifecycleControlState;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleState;
import com.iocextractor.application.artifact.lifecycle.LifecycleStatusSnapshot;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleClockInspector;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleStatusReader;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only aggregate SQLite lifecycle status; never returns IOC or source values. */
public final class JdbcLifecycleStatusReader implements LifecycleStatusReader {

    private final DataSource dataSource;
    private final List<String> artifacts;
    private final LifecycleClockInspector clockInspector;

    public JdbcLifecycleStatusReader(DataSource dataSource,
                                     List<DataframeArtifactSchema> schemas,
                                     LifecycleClockInspector clockInspector) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.artifacts = Objects.requireNonNull(schemas, "schemas").stream()
                .map(DataframeArtifactSchema::artifactName)
                .sorted()
                .toList();
        this.clockInspector = Objects.requireNonNull(clockInspector, "clockInspector");
    }

    @Override
    public LifecycleStatusSnapshot read() {
        LifecycleClockSnapshot clock = clockInspector.inspect();
        long asOf = clock.effectiveTime().value().toEpochMilli();
        try (Connection connection = dataSource.getConnection()) {
            LifecycleControlState control = readControl(connection);
            List<LifecycleArtifactStatistics> statistics = new ArrayList<>(artifacts.size());
            Optional<Long> nearest = Optional.empty();
            Optional<Long> oldestDue = Optional.empty();
            for (String artifact : artifacts) {
                statistics.add(readArtifactStatistics(connection, artifact, asOf));
                nearest = minimum(nearest, minimum(connection, artifact, null));
                oldestDue = minimum(oldestDue, minimum(connection, artifact, asOf));
            }
            LatestCycle latest = readLatestCycle(connection);
            long pending = countPendingProjections(connection);
            Duration backlogAge = oldestDue
                    .map(value -> Duration.ofMillis(Math.max(0L, asOf - value)))
                    .orElse(Duration.ZERO);
            return new LifecycleStatusSnapshot(
                    control,
                    clock,
                    statistics,
                    nearest.map(Instant::ofEpochMilli),
                    oldestDue.map(Instant::ofEpochMilli),
                    pending,
                    latest.state(),
                    latest.startedAt(),
                    latest.completedAt(),
                    latest.expired(),
                    latest.failureCode(),
                    backlogAge);
        } catch (SQLException | RuntimeException e) {
            if (e instanceof IocExtractorException iocFailure) {
                throw iocFailure;
            }
            throw new IocExtractorException("Failed to read aggregate lifecycle status", e);
        }
    }

    private LifecycleControlState readControl(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version, state, policy_fingerprint, activated_at_ms
                FROM canonical_lifecycle_control WHERE singleton_id = 1
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Lifecycle control row is missing");
                }
                return new LifecycleControlState(
                        resultSet.getLong("version"),
                        LifecycleActivationState.valueOf(resultSet.getString("state")),
                        Optional.ofNullable(resultSet.getString("policy_fingerprint")),
                        optionalEffectiveTime(resultSet, "activated_at_ms"));
            }
        }
    }

    private LifecycleArtifactStatistics readArtifactStatistics(Connection connection,
                                                                String artifact,
                                                                long asOf) throws SQLException {
        long stored = count(connection, artifact);
        long due = countDue(connection, artifact, asOf);
        long history = count(connection, artifact + "_history");
        return new LifecycleArtifactStatistics(artifact, stored, due, history);
    }

    private long countDue(Connection connection, String artifact, long asOf) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + quote(artifact) + " WHERE "
                + quote("_valid_until_epoch_ms") + " <= ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private Optional<Long> minimum(Connection connection,
                                   String artifact,
                                   Long dueAtOrBefore) throws SQLException {
        String predicate = dueAtOrBefore == null ? "" : " WHERE "
                + quote("_valid_until_epoch_ms") + " <= ?";
        String sql = "SELECT MIN(" + quote("_valid_until_epoch_ms") + ") FROM "
                + quote(artifact) + predicate;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (dueAtOrBefore != null) {
                statement.setLong(1, dueAtOrBefore);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                long value = resultSet.getLong(1);
                return resultSet.wasNull() ? Optional.empty() : Optional.of(value);
            }
        }
    }

    private long count(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + quote(table));
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long countPendingProjections(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM artifact_projection_state
                WHERE projected_generation < required_generation
                """); ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private LatestCycle readLatestCycle(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state, started_at_ms, completed_at_ms, expired_count, failure_code
                FROM lifecycle_reconcile_cycle
                ORDER BY cycle_id DESC LIMIT 1
                """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return LatestCycle.neverRun();
            }
            return new LatestCycle(
                    LifecycleReconcileCycleState.valueOf(resultSet.getString("state")),
                    Optional.of(Instant.ofEpochMilli(resultSet.getLong("started_at_ms"))),
                    optionalInstant(resultSet, "completed_at_ms"),
                    resultSet.getLong("expired_count"),
                    Optional.ofNullable(resultSet.getString("failure_code")));
        }
    }

    private Optional<EffectiveTime> optionalEffectiveTime(ResultSet resultSet,
                                                          String column) throws SQLException {
        return optionalInstant(resultSet, column).map(EffectiveTime::at);
    }

    private Optional<Instant> optionalInstant(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(value));
    }

    private Optional<Long> minimum(Optional<Long> left, Optional<Long> right) {
        return left.stream().flatMap(value -> right.stream().map(other -> Math.min(value, other)))
                .findFirst()
                .or(() -> left)
                .or(() -> right);
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record LatestCycle(LifecycleReconcileCycleState state,
                               Optional<Instant> startedAt,
                               Optional<Instant> completedAt,
                               long expired,
                               Optional<String> failureCode) {

        private static LatestCycle neverRun() {
            return new LatestCycle(LifecycleReconcileCycleState.NEVER_RUN,
                    Optional.empty(), Optional.empty(), 0L, Optional.empty());
        }
    }
}
