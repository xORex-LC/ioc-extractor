package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.observation.ObservationAdmissionReference;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.observation.ObservationAdmissionReferenceStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Service-DB recovery reference for managed-import observation admission. */
public final class JdbcObservationAdmissionReferenceStore
        implements ObservationAdmissionReferenceStore {

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcObservationAdmissionReferenceStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ObservationAdmissionReference link(RegisteredObservation registration) {
        Objects.requireNonNull(registration, "registration");
        if (registration.origin() != ObservationOrigin.MANAGED_IMPORT) {
            throw new IllegalArgumentException("Only managed-import references are stored here");
        }
        try (Connection connection = dataSource.getConnection()) {
            beginImmediate(connection);
            try {
                ObservationAdmissionReference existing = find(connection, registration.observationId());
                if (existing != null) {
                    if (!existing.registration().equals(registration)) {
                        throw new IllegalStateException("Observation admission reference changed on retry");
                    }
                    commit(connection);
                    return existing;
                }
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO observation_admission_reference(
                          occurrence_id, origin_kind, dataframe_namespace, admission_order,
                          version, registration_finalized, created_at_ms, updated_at_ms)
                        VALUES (?, 'MANAGED_IMPORT', ?, ?, 0, 0, ?, ?)
                        """)) {
                    statement.setString(1, registration.observationId().value());
                    statement.setString(2, registration.namespaceId());
                    statement.setLong(3, registration.admissionOrder().value());
                    statement.setLong(4, now.toEpochMilli());
                    statement.setLong(5, now.toEpochMilli());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Observation reference insert changed no rows");
                    }
                }
                commit(connection);
                return new ObservationAdmissionReference(registration, 0, Optional.empty(),
                        false, now, now);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to link observation admission reference", failure);
        }
    }

    @Override
    public Optional<ObservationAdmissionReference> find(ObservationId observationId) {
        try (Connection connection = dataSource.getConnection()) {
            return Optional.ofNullable(find(connection, observationId));
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to read observation admission reference", failure);
        }
    }

    @Override
    public boolean replace(ObservationAdmissionReference expected,
                           ObservationAdmissionReference updated) {
        if (!expected.registration().observationId().equals(updated.registration().observationId())
                || !expected.registration().equals(updated.registration())
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Observation reference replacement must advance one version");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE observation_admission_reference
                     SET version = ?, terminal_outcome = ?, registration_finalized = ?, updated_at_ms = ?
                     WHERE occurrence_id = ? AND version = ?
                     """)) {
            statement.setLong(1, updated.version());
            statement.setString(2, updated.terminalOutcome().orElse(null));
            statement.setInt(3, updated.registrationFinalized() ? 1 : 0);
            statement.setLong(4, updated.updatedAt().toEpochMilli());
            statement.setString(5, expected.registration().observationId().value());
            statement.setLong(6, expected.version());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to advance observation admission reference", failure);
        }
    }

    @Override
    public List<ObservationAdmissionReference> findUnfinalized(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Observation reference query limit must be positive");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT occurrence_id, origin_kind, dataframe_namespace, admission_order,
                            version, terminal_outcome, registration_finalized,
                            created_at_ms, updated_at_ms
                     FROM observation_admission_reference
                     WHERE registration_finalized = 0
                     ORDER BY created_at_ms, occurrence_id LIMIT ?
                     """)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ObservationAdmissionReference> references = new ArrayList<>();
                while (result.next()) {
                    references.add(map(result));
                }
                return List.copyOf(references);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to query observation admission references", failure);
        }
    }

    @Override
    public List<ObservationAdmissionReference> findFinalizedBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1) {
            throw new IllegalArgumentException("Observation reference query limit must be positive");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT occurrence_id, origin_kind, dataframe_namespace, admission_order,
                            version, terminal_outcome, registration_finalized,
                            created_at_ms, updated_at_ms
                     FROM observation_admission_reference
                     WHERE registration_finalized = 1 AND updated_at_ms < ?
                     ORDER BY updated_at_ms, occurrence_id LIMIT ?
                     """)) {
            statement.setLong(1, cutoff.toEpochMilli());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<ObservationAdmissionReference> references = new ArrayList<>();
                while (result.next()) {
                    references.add(map(result));
                }
                return List.copyOf(references);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to query terminal observation references", failure);
        }
    }

    @Override
    public boolean purgeFinalized(ObservationId observationId, long expectedVersion) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM observation_admission_reference
                     WHERE occurrence_id = ? AND version = ? AND registration_finalized = 1
                     """)) {
            statement.setString(1, observationId.value());
            statement.setLong(2, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to purge observation admission reference", failure);
        }
    }

    private ObservationAdmissionReference find(Connection connection, ObservationId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT occurrence_id, origin_kind, dataframe_namespace, admission_order,
                       version, terminal_outcome, registration_finalized,
                       created_at_ms, updated_at_ms
                FROM observation_admission_reference WHERE occurrence_id = ?
                """)) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private ObservationAdmissionReference map(ResultSet result) throws SQLException {
        ObservationOrigin origin = ObservationOrigin.valueOf(result.getString("origin_kind"));
        RegisteredObservation registration = new RegisteredObservation(
                new ObservationId(result.getString("occurrence_id")),
                result.getString("dataframe_namespace"),
                new ObservationOrder(result.getLong("admission_order")), origin);
        return new ObservationAdmissionReference(registration, result.getLong("version"),
                Optional.ofNullable(result.getString("terminal_outcome")),
                result.getInt("registration_finalized") == 1,
                Instant.ofEpochMilli(result.getLong("created_at_ms")),
                Instant.ofEpochMilli(result.getLong("updated_at_ms")));
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
}
