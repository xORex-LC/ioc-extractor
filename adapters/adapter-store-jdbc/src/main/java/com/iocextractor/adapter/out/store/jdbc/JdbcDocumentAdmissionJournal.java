package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.admission.DocumentAdmission;
import com.iocextractor.application.ingest.admission.DocumentAdmissionPhase;
import com.iocextractor.application.ingest.admission.DocumentAdmissionReservation;
import com.iocextractor.application.ingest.admission.DocumentCandidateEvidence;
import com.iocextractor.application.ingest.admission.DocumentTerminalOutcome;
import com.iocextractor.application.observation.ObservationOrder;
import com.iocextractor.application.observation.ObservationOrigin;
import com.iocextractor.application.observation.RegisteredObservation;
import com.iocextractor.application.port.out.ingest.DocumentAdmissionJournal;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Service-DB document admission journal with short SQLite CAS transactions. */
public final class JdbcDocumentAdmissionJournal implements DocumentAdmissionJournal {

    private static final String COLUMNS = """
            occurrence_id, candidate_path, candidate_file_key, candidate_size, candidate_mtime_ns,
            claim_path, claimed_file_key, claimed_size, claimed_mtime_ns, version, phase,
            dataframe_namespace, admission_order, source_key, terminal_outcome,
            registration_finalized, created_at_ms, updated_at_ms
            """;

    private final DataSource dataSource;

    public JdbcDocumentAdmissionJournal(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public DocumentAdmission reserve(DocumentAdmissionReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        try (Connection connection = dataSource.getConnection()) {
            beginImmediate(connection);
            try {
                DocumentAdmission existing = find(connection, reservation.observationId());
                if (existing != null) {
                    requireSameReservation(existing, reservation);
                    commit(connection);
                    return existing;
                }
                DocumentAdmission created = DocumentAdmission.reserved(reservation);
                insert(connection, created);
                commit(connection);
                return created;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to reserve document admission", failure);
        }
    }

    @Override
    public Optional<DocumentAdmission> find(ObservationId observationId) {
        Objects.requireNonNull(observationId, "observationId");
        try (Connection connection = dataSource.getConnection()) {
            return Optional.ofNullable(find(connection, observationId));
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to read document admission", failure);
        }
    }

    @Override
    public boolean replace(DocumentAdmission expected, DocumentAdmission updated) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(updated, "updated");
        if (!expected.observationId().equals(updated.observationId())
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Document admission replacement must advance one version");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE document_admission SET
                       claimed_file_key = ?, claimed_size = ?, claimed_mtime_ns = ?,
                       version = ?, phase = ?, dataframe_namespace = ?, admission_order = ?,
                       source_key = ?, terminal_outcome = ?, registration_finalized = ?, updated_at_ms = ?
                     WHERE occurrence_id = ? AND version = ? AND phase = ?
                     """)) {
            bindOptionalEvidence(statement, 1, updated.claimedEvidence());
            statement.setLong(4, updated.version());
            statement.setString(5, updated.phase().name());
            bindRegistration(statement, 6, updated.registration());
            statement.setString(8, updated.sourceKey().map(SourceKey::value).orElse(null));
            statement.setString(9, updated.terminalOutcome().map(Enum::name).orElse(null));
            statement.setInt(10, updated.registrationFinalized() ? 1 : 0);
            statement.setLong(11, updated.updatedAt().toEpochMilli());
            statement.setString(12, expected.observationId().value());
            statement.setLong(13, expected.version());
            statement.setString(14, expected.phase().name());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to advance document admission", failure);
        }
    }

    @Override
    public List<DocumentAdmission> findRecoverable(int limit) {
        return query("""
                SELECT %s FROM document_admission
                WHERE phase <> 'TERMINAL' OR registration_finalized = 0
                ORDER BY created_at_ms, occurrence_id LIMIT ?
                """.formatted(COLUMNS), null, limit);
    }

    @Override
    public List<DocumentAdmission> findTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        return query("""
                SELECT %s FROM document_admission
                WHERE phase = 'TERMINAL' AND registration_finalized = 1 AND updated_at_ms < ?
                ORDER BY updated_at_ms, occurrence_id LIMIT ?
                """.formatted(COLUMNS), cutoff, limit);
    }

    @Override
    public boolean purgeTerminal(ObservationId observationId, long expectedVersion) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM document_admission
                     WHERE occurrence_id = ? AND version = ?
                       AND phase = 'TERMINAL' AND registration_finalized = 1
                     """)) {
            statement.setString(1, observationId.value());
            statement.setLong(2, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to purge document admission", failure);
        }
    }

    private List<DocumentAdmission> query(String sql, Instant cutoff, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Document admission query limit must be positive");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            if (cutoff != null) {
                statement.setLong(parameter++, cutoff.toEpochMilli());
            }
            statement.setInt(parameter, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<DocumentAdmission> admissions = new ArrayList<>();
                while (result.next()) {
                    admissions.add(map(result));
                }
                return List.copyOf(admissions);
            }
        } catch (SQLException failure) {
            throw new IocExtractorException("Failed to query document admissions", failure);
        }
    }

    private void insert(Connection connection, DocumentAdmission admission) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document_admission(
                  occurrence_id, candidate_path, candidate_file_key, candidate_size,
                  candidate_mtime_ns, claim_path, version, phase, registration_finalized,
                  created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 0, 'RESERVED', 0, ?, ?)
                """)) {
            statement.setString(1, admission.observationId().value());
            statement.setString(2, admission.candidatePath().toString());
            statement.setString(3, admission.candidateEvidence().fileKey().orElse(null));
            statement.setLong(4, admission.candidateEvidence().size());
            statement.setLong(5, admission.candidateEvidence().modifiedAtNanos());
            statement.setString(6, admission.claimPath().toString());
            statement.setLong(7, admission.createdAt().toEpochMilli());
            statement.setLong(8, admission.updatedAt().toEpochMilli());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Document reservation insert changed no rows");
            }
        }
    }

    private DocumentAdmission find(Connection connection, ObservationId id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM document_admission WHERE occurrence_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private DocumentAdmission map(ResultSet result) throws SQLException {
        ObservationId id = new ObservationId(result.getString("occurrence_id"));
        DocumentAdmissionPhase phase = DocumentAdmissionPhase.valueOf(result.getString("phase"));
        Optional<RegisteredObservation> registration = Optional.empty();
        String namespace = result.getString("dataframe_namespace");
        if (namespace != null) {
            registration = Optional.of(new RegisteredObservation(id, namespace,
                    new ObservationOrder(result.getLong("admission_order")), ObservationOrigin.DOCUMENT));
        }
        Optional<DocumentCandidateEvidence> claimed = Optional.empty();
        Long claimedSize = nullableLong(result, "claimed_size");
        if (claimedSize != null) {
            claimed = Optional.of(new DocumentCandidateEvidence(
                    Optional.ofNullable(result.getString("claimed_file_key")), claimedSize,
                    result.getLong("claimed_mtime_ns")));
        }
        return new DocumentAdmission(id,
                Path.of(result.getString("candidate_path")),
                new DocumentCandidateEvidence(
                        Optional.ofNullable(result.getString("candidate_file_key")),
                        result.getLong("candidate_size"), result.getLong("candidate_mtime_ns")),
                Path.of(result.getString("claim_path")), claimed, phase, result.getLong("version"),
                registration, Optional.ofNullable(result.getString("source_key")).map(SourceKey::new),
                Optional.ofNullable(result.getString("terminal_outcome"))
                        .map(DocumentTerminalOutcome::valueOf),
                result.getInt("registration_finalized") == 1,
                Instant.ofEpochMilli(result.getLong("created_at_ms")),
                Instant.ofEpochMilli(result.getLong("updated_at_ms")));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private void bindOptionalEvidence(PreparedStatement statement, int offset,
                                      Optional<DocumentCandidateEvidence> evidence) throws SQLException {
        statement.setString(offset, evidence.flatMap(DocumentCandidateEvidence::fileKey).orElse(null));
        if (evidence.isPresent()) {
            statement.setLong(offset + 1, evidence.orElseThrow().size());
            statement.setLong(offset + 2, evidence.orElseThrow().modifiedAtNanos());
        } else {
            statement.setNull(offset + 1, java.sql.Types.BIGINT);
            statement.setNull(offset + 2, java.sql.Types.BIGINT);
        }
    }

    private void bindRegistration(PreparedStatement statement, int offset,
                                  Optional<RegisteredObservation> registration) throws SQLException {
        statement.setString(offset, registration.map(RegisteredObservation::namespaceId).orElse(null));
        if (registration.isPresent()) {
            statement.setLong(offset + 1, registration.orElseThrow().admissionOrder().value());
        } else {
            statement.setNull(offset + 1, java.sql.Types.BIGINT);
        }
    }

    private void requireSameReservation(DocumentAdmission existing,
                                        DocumentAdmissionReservation reservation) {
        if (!existing.candidatePath().equals(reservation.candidatePath())
                || !existing.candidateEvidence().equals(reservation.candidateEvidence())
                || !existing.claimPath().equals(reservation.claimPath())) {
            throw new IllegalStateException("Document occurrence reservation changed on retry");
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
}
