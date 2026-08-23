package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockPolicy;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockSnapshot;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockStatus;
import com.iocextractor.application.artifact.lifecycle.LifecycleClockUnsafeException;
import com.iocextractor.application.artifact.lifecycle.LifecycleTimeSource;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleClockInspector;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * SQLite-backed nondecreasing lifecycle clock.
 *
 * <p>The durable UTC high-water mark prevents a wall-clock rollback from
 * extending active lifecycles silently. Monotonic time is used only to bound
 * how long this process may remain clamped; it never becomes a persisted
 * business timestamp.
 */
public final class JdbcLifecycleClock implements LifecycleTimeSource, LifecycleClockInspector {

    private final DataSource dataSource;
    private final Clock wallClock;
    private final LifecycleClockPolicy policy;
    private final LongSupplier monotonicNanos;
    private final Object clampMonitor = new Object();
    private Long localClampStartedNanos;

    /** Creates a safe clock from system UTC time and {@link System#nanoTime()}. */
    public JdbcLifecycleClock(DataSource dataSource,
                              Clock wallClock,
                              LifecycleClockPolicy policy) {
        this(dataSource, wallClock, policy, System::nanoTime);
    }

    JdbcLifecycleClock(DataSource dataSource,
                       Clock wallClock,
                       LifecycleClockPolicy policy,
                       LongSupplier monotonicNanos) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    @Override
    public EffectiveTime now() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                EffectiveTime result = now(connection);
                connection.commit();
                return result;
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
            throw new IocExtractorException("Failed to establish safe lifecycle time", e);
        }
    }

    /**
     * Samples and advances safe time inside an already owned JDBC transaction.
     * The caller controls commit or rollback.
     */
    EffectiveTime now(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Instant raw = wallClock.instant();
        long rawMillis = raw.toEpochMilli();
        acquireWriteOwnership(connection);
        ClockRow row = readRow(connection);
        if (row.highWaterMillis() == null || rawMillis >= row.highWaterMillis()) {
            updateClockState(connection, rawMillis, null);
            clearLocalClamp();
            return EffectiveTime.at(raw);
        }

        Duration skew = Duration.ofMillis(Math.subtractExact(row.highWaterMillis(), rawMillis));
        if (skew.compareTo(policy.maxBackwardSkew()) > 0) {
            throw unsafe("System UTC clock is behind the durable lifecycle high-water by " + skew);
        }

        long clampStartMillis = row.clampStartedAtMillis() == null
                ? rawMillis
                : row.clampStartedAtMillis();
        Duration clampAge = clampAge(rawMillis, clampStartMillis);
        if (clampAge.compareTo(policy.maxClampDuration()) > 0) {
            throw unsafe("Lifecycle clock remained clamped for " + clampAge);
        }
        updateClockState(connection, row.highWaterMillis(), clampStartMillis);
        return EffectiveTime.at(Instant.ofEpochMilli(row.highWaterMillis()));
    }

    @Override
    public LifecycleClockSnapshot inspect() {
        Instant raw = wallClock.instant();
        try (Connection connection = dataSource.getConnection()) {
            ClockRow row = readRow(connection);
            if (row.highWaterMillis() == null || raw.toEpochMilli() >= row.highWaterMillis()) {
                return snapshot(LifecycleClockStatus.SAFE, raw, raw, row, Duration.ZERO, Duration.ZERO);
            }
            Duration skew = Duration.ofMillis(row.highWaterMillis() - raw.toEpochMilli());
            Duration clampAge = row.clampStartedAtMillis() == null
                    ? Duration.ZERO
                    : clampAgeReadOnly(raw.toEpochMilli(), row.clampStartedAtMillis());
            LifecycleClockStatus status = skew.compareTo(policy.maxBackwardSkew()) > 0
                    || clampAge.compareTo(policy.maxClampDuration()) > 0
                    ? LifecycleClockStatus.UNSAFE
                    : LifecycleClockStatus.CLAMPED;
            return snapshot(status, raw, Instant.ofEpochMilli(row.highWaterMillis()), row, skew, clampAge);
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to inspect lifecycle clock", e);
        }
    }

    private LifecycleClockSnapshot snapshot(LifecycleClockStatus status,
                                             Instant raw,
                                             Instant effective,
                                             ClockRow row,
                                             Duration skew,
                                             Duration clampAge) {
        Optional<EffectiveTime> highWater = Optional.ofNullable(row.highWaterMillis())
                .map(Instant::ofEpochMilli)
                .map(EffectiveTime::at);
        return new LifecycleClockSnapshot(
                status, raw, EffectiveTime.at(effective), highWater, skew, clampAge);
    }

    private void acquireWriteOwnership(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE canonical_lifecycle_control
                SET safe_time_high_water_ms = safe_time_high_water_ms
                WHERE singleton_id = 1
                """)) {
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Lifecycle clock control row is missing");
            }
        }
    }

    private ClockRow readRow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT safe_time_high_water_ms, clamp_started_at_ms
                FROM canonical_lifecycle_control
                WHERE singleton_id = 1
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Lifecycle clock control row is missing");
                }
                return new ClockRow(nullableLong(resultSet, "safe_time_high_water_ms"),
                        nullableLong(resultSet, "clamp_started_at_ms"));
            }
        }
    }

    private void updateClockState(Connection connection,
                                  long highWaterMillis,
                                  Long clampStartedAtMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE canonical_lifecycle_control
                SET safe_time_high_water_ms = ?, clamp_started_at_ms = ?
                WHERE singleton_id = 1
                """)) {
            statement.setLong(1, highWaterMillis);
            if (clampStartedAtMillis == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, clampStartedAtMillis);
            }
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Lifecycle clock control row is missing");
            }
        }
    }

    private Duration clampAge(long rawMillis, long persistedStartMillis) {
        long nowNanos = monotonicNanos.getAsLong();
        synchronized (clampMonitor) {
            if (localClampStartedNanos == null) {
                localClampStartedNanos = nowNanos;
            }
            Duration local = Duration.ofNanos(Math.max(0L, nowNanos - localClampStartedNanos));
            Duration persisted = Duration.ofMillis(Math.max(0L, rawMillis - persistedStartMillis));
            return local.compareTo(persisted) >= 0 ? local : persisted;
        }
    }

    private Duration clampAgeReadOnly(long rawMillis, long persistedStartMillis) {
        Duration persisted = Duration.ofMillis(Math.max(0L, rawMillis - persistedStartMillis));
        synchronized (clampMonitor) {
            if (localClampStartedNanos == null) {
                return persisted;
            }
            long elapsed = Math.max(0L, monotonicNanos.getAsLong() - localClampStartedNanos);
            Duration local = Duration.ofNanos(elapsed);
            return local.compareTo(persisted) >= 0 ? local : persisted;
        }
    }

    private void clearLocalClamp() {
        synchronized (clampMonitor) {
            localClampStartedNanos = null;
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private LifecycleClockUnsafeException unsafe(String message) {
        return new LifecycleClockUnsafeException(message);
    }

    private record ClockRow(Long highWaterMillis, Long clampStartedAtMillis) {
    }
}
