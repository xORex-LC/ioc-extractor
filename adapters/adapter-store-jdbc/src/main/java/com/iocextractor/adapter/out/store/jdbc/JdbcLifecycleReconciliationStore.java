package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleReconcileCycleId;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleReconciliationStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** SQLite journal for recoverable aggregate expiration reconciliation cycles. */
public final class JdbcLifecycleReconciliationStore implements LifecycleReconciliationStore {

    private final DataSource dataSource;

    public JdbcLifecycleReconciliationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public int failInterrupted(EffectiveTime recoveredAt, String failureCode) {
        requireFailureCode(failureCode);
        String sql = """
                UPDATE lifecycle_reconcile_cycle
                SET state = 'FAILED', completed_at_ms = ?, failure_code = ?
                WHERE state = 'STARTED'
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, epochMillis(recoveredAt));
            statement.setString(2, failureCode);
            return statement.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            throw failure("recover interrupted lifecycle reconciliation", e);
        }
    }

    @Override
    public LifecycleReconcileCycleId start(EffectiveTime cycleAsOf) {
        String sql = """
                INSERT INTO lifecycle_reconcile_cycle(
                    cycle_as_of_ms, state, started_at_ms)
                VALUES (?, 'STARTED', ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long time = epochMillis(cycleAsOf);
            statement.setLong(1, time);
            statement.setLong(2, time);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Lifecycle reconciliation cycle was not created");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IocExtractorException("Lifecycle reconciliation cycle ID was not returned");
                }
                return new LifecycleReconcileCycleId(keys.getLong(1));
            }
        } catch (SQLException | RuntimeException e) {
            throw failure("start lifecycle reconciliation", e);
        }
    }

    @Override
    public void recordBatch(LifecycleReconcileCycleId cycleId, int expired) {
        if (expired <= 0) {
            throw new IllegalArgumentException("expired batch count must be positive");
        }
        updateStarted("""
                UPDATE lifecycle_reconcile_cycle
                SET expired_count = expired_count + ?
                WHERE cycle_id = ? AND state = 'STARTED'
                """, statement -> {
            statement.setInt(1, expired);
            statement.setLong(2, requireCycle(cycleId));
        }, "record lifecycle reconciliation batch");
    }

    @Override
    public void complete(LifecycleReconcileCycleId cycleId,
                         EffectiveTime completedAt,
                         int expired,
                         int affectedArtifacts) {
        if (expired < 0 || affectedArtifacts < 0) {
            throw new IllegalArgumentException("terminal lifecycle counters must not be negative");
        }
        updateStarted("""
                UPDATE lifecycle_reconcile_cycle
                SET state = 'COMPLETED', completed_at_ms = ?, expired_count = ?,
                    affected_artifact_count = ?, failure_code = NULL
                WHERE cycle_id = ? AND state = 'STARTED'
                """, statement -> {
            statement.setLong(1, epochMillis(completedAt));
            statement.setInt(2, expired);
            statement.setInt(3, affectedArtifacts);
            statement.setLong(4, requireCycle(cycleId));
        }, "complete lifecycle reconciliation");
    }

    @Override
    public void fail(LifecycleReconcileCycleId cycleId,
                     EffectiveTime failedAt,
                     String failureCode) {
        requireFailureCode(failureCode);
        updateStarted("""
                UPDATE lifecycle_reconcile_cycle
                SET state = 'FAILED', completed_at_ms = ?, failure_code = ?
                WHERE cycle_id = ? AND state = 'STARTED'
                """, statement -> {
            statement.setLong(1, epochMillis(failedAt));
            statement.setString(2, failureCode);
            statement.setLong(3, requireCycle(cycleId));
        }, "fail lifecycle reconciliation");
    }

    private void updateStarted(String sql, StatementBinder binder, String action) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException(
                        "Lifecycle reconciliation cycle is not in STARTED state");
            }
        } catch (SQLException | RuntimeException e) {
            throw failure(action, e);
        }
    }

    private long epochMillis(EffectiveTime time) {
        return Objects.requireNonNull(time, "time").value().toEpochMilli();
    }

    private long requireCycle(LifecycleReconcileCycleId cycleId) {
        return Objects.requireNonNull(cycleId, "cycleId").value();
    }

    private void requireFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode must not be blank");
        }
    }

    private IocExtractorException failure(String action, Exception failure) {
        if (failure instanceof IocExtractorException iocFailure) {
            return iocFailure;
        }
        return new IocExtractorException("Failed to " + action, failure);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
