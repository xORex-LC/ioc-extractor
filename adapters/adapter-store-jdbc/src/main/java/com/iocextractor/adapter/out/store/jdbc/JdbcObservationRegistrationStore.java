package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.observation.ObservationRegistrationStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Objects;

/** SQLite order authority: one short write transaction, never nested with service storage. */
public final class JdbcObservationRegistrationStore implements ObservationRegistrationStore {

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcObservationRegistrationStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RegisteredObservation registerNew(ObservationId observationId, ObservationOrigin origin) {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(origin, "origin");
        try (Connection connection = dataSource.getConnection()) {
            beginImmediate(connection);
            try {
                Control control = control(connection);
                RegisteredObservation existing = find(connection, observationId, control.namespaceId());
                if (existing != null) {
                    if (existing.origin() != origin) {
                        throw new IllegalStateException("Occurrence origin changed on registration retry");
                    }
                    commit(connection);
                    return existing;
                }
                if (control.nextOrder() == Long.MAX_VALUE) {
                    throw new IllegalStateException("Observation order exhausted");
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE observation_order_control SET next_order = ? WHERE singleton_id = 1")) {
                    update.setLong(1, control.nextOrder() + 1);
                    requireOne(update.executeUpdate());
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO registered_observation
                        (occurrence_id, admission_order, origin_kind, registered_at_ms)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    insert.setString(1, observationId.value());
                    insert.setLong(2, control.nextOrder());
                    insert.setString(3, origin.name());
                    insert.setLong(4, clock.millis());
                    requireOne(insert.executeUpdate());
                }
                commit(connection);
                return new RegisteredObservation(observationId, control.namespaceId(),
                        new ObservationOrder(control.nextOrder()), origin);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to register observation order", failure);
        }
    }

    @Override
    public RegisteredObservation resume(ObservationId observationId, String expectedNamespace) {
        Objects.requireNonNull(observationId, "observationId");
        try (Connection connection = dataSource.getConnection()) {
            String namespace = control(connection).namespaceId();
            requireNamespace(expectedNamespace, namespace);
            RegisteredObservation existing = find(connection, observationId, namespace);
            if (existing == null) {
                throw new IllegalStateException("Missing registered observation on recovery");
            }
            return existing;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to resume observation order", failure);
        }
    }

    @Override
    public void markTerminal(ObservationId observationId, String expectedNamespace) {
        RegisteredObservation registration = resume(observationId, expectedNamespace);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE registered_observation
                     SET terminal_at_ms = COALESCE(terminal_at_ms, ?)
                     WHERE occurrence_id = ? AND admission_order = ?
                     """)) {
            update.setLong(1, clock.millis());
            update.setString(2, observationId.value());
            update.setLong(3, registration.admissionOrder().value());
            requireOne(update.executeUpdate());
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to finalize observation registration", failure);
        }
    }

    @Override
    public boolean purgeTerminal(RegisteredObservation registration) {
        Objects.requireNonNull(registration, "registration");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM registered_observation
                     WHERE occurrence_id = ? AND admission_order = ? AND terminal_at_ms IS NOT NULL
                       AND NOT EXISTS (
                         SELECT 1 FROM canonical_lifecycle_field_origin lifecycle_origin
                         WHERE lifecycle_origin.occurrence_id = registered_observation.occurrence_id)
                       AND NOT EXISTS (
                         SELECT 1 FROM canonical_compat_field_origin compat_origin
                         WHERE compat_origin.occurrence_id = registered_observation.occurrence_id)
                     """)) {
            statement.setString(1, registration.observationId().value());
            statement.setLong(2, registration.admissionOrder().value());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to purge observation registrations", failure);
        }
    }

    private Control control(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT namespace_id, next_order FROM observation_order_control WHERE singleton_id = 1
                     """)) {
            if (!result.next()) {
                throw new IllegalStateException("Missing observation order control row");
            }
            return new Control(result.getString(1), result.getLong(2));
        }
    }

    private RegisteredObservation find(Connection connection, ObservationId id, String namespace)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT admission_order, origin_kind FROM registered_observation WHERE occurrence_id = ?
                """)) {
            query.setString(1, id.value());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new RegisteredObservation(id, namespace,
                        new ObservationOrder(result.getLong(1)),
                        ObservationOrigin.valueOf(result.getString(2))) : null;
            }
        }
    }

    private void requireNamespace(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new IllegalStateException("Dataframe observation namespace differs from journal");
        }
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("Observation order transaction changed " + changed + " rows");
        }
    }

    private void beginImmediate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }
    }

    private void commit(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("COMMIT");
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private record Control(String namespaceId, long nextOrder) {
    }
}
