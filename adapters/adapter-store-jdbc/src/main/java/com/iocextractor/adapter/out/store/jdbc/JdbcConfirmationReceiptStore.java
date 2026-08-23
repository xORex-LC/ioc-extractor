package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptArtifact;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptSnapshot;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.port.out.artifact.lifecycle.CanonicalObservationStore;
import com.iocextractor.application.port.out.artifact.lifecycle.ConfirmationReceiptStore;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** SQLite complete-receipt reader, retention reaper and observation acknowledgement. */
public final class JdbcConfirmationReceiptStore
        implements ConfirmationReceiptStore, CanonicalObservationStore {

    private final DataSource dataSource;
    private final Map<String, DataframeArtifactSchema> schemas;
    private final Duration stagingRetention;

    public JdbcConfirmationReceiptStore(DataSource dataSource, List<DataframeArtifactSchema> schemas) {
        this(dataSource, schemas, Duration.ofDays(30));
    }

    public JdbcConfirmationReceiptStore(DataSource dataSource,
                                        List<DataframeArtifactSchema> schemas,
                                        Duration stagingRetention) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.stagingRetention = Objects.requireNonNull(stagingRetention, "stagingRetention");
        if (stagingRetention.isZero() || stagingRetention.isNegative()) {
            throw new IllegalArgumentException("Staging receipt retention must be positive");
        }
        Objects.requireNonNull(schemas, "schemas");
        var byName = new LinkedHashMap<String, DataframeArtifactSchema>();
        schemas.stream().sorted(Comparator.comparing(DataframeArtifactSchema::artifactName))
                .forEach(schema -> {
                    if (byName.put(schema.artifactName(), schema) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate dataframe artifact schema: " + schema.artifactName());
                    }
                });
        this.schemas = Map.copyOf(byName);
    }

    @Override
    public Optional<ConfirmationReceiptSnapshot> findComplete(
            String sourceKey, String processingPolicyFingerprint, EffectiveTime asOf) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(processingPolicyFingerprint, "processingPolicyFingerprint");
        Objects.requireNonNull(asOf, "asOf");
        try (Connection connection = dataSource.getConnection()) {
            Optional<ReceiptHeader> header = findHeader(
                    connection, sourceKey, processingPolicyFingerprint, asOf);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            ReceiptHeader selected = header.orElseThrow();
            List<ConfirmationReceiptArtifact> artifacts = loadArtifacts(connection, selected);
            return Optional.of(new ConfirmationReceiptSnapshot(
                    selected.id(), sourceKey, processingPolicyFingerprint, artifacts));
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to load a complete confirmation receipt", e);
        }
    }

    @Override
    public PurgeResult purgeExpired(EffectiveTime asOf, int batchSize) {
        Objects.requireNonNull(asOf, "asOf");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Receipt purge batch size must be positive");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Exception failure = null;
            try {
                int purged = purgeReceipts(connection, asOf, batchSize);
                purgeObservations(connection, asOf, batchSize);
                boolean more = hasExpiredReceipts(connection, asOf);
                connection.commit();
                return new PurgeResult(purged, more);
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
            throw new IocExtractorException("Failed to purge confirmation receipts", e);
        }
    }

    @Override
    public void markTerminal(ObservationId observationId,
                             EffectiveTime completedAt,
                             Duration retention) {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Observation retention must be positive");
        }
        long completedAtMs = completedAt.value().toEpochMilli();
        long purgeAfterMs = completedAt.value().plus(retention).toEpochMilli();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE canonical_observation
                     SET state = 'TERMINAL', terminal_at_ms = ?, purge_after_ms = ?
                     WHERE observation_id = ? AND state = 'OPEN'
                     """)) {
            statement.setLong(1, completedAtMs);
            statement.setLong(2, purgeAfterMs);
            statement.setString(3, observationId.value());
            if (statement.executeUpdate() == 1 || isTerminal(connection, observationId)) {
                return;
            }
            // The attempt may have failed before its first canonical artifact commit.
        } catch (IocExtractorException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new IocExtractorException("Failed to mark canonical observation terminal", e);
        }
    }

    private Optional<ReceiptHeader> findHeader(Connection connection,
                                               String sourceKey,
                                               String fingerprint,
                                               EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT receipt_id, expected_artifacts, row_count
                FROM confirmation_receipt
                WHERE source_key = ?
                  AND processing_policy_fingerprint = ?
                  AND state = 'COMPLETE'
                  AND purge_after_ms > ?
                ORDER BY completed_at_ms DESC, receipt_id DESC
                LIMIT 1
                """)) {
            statement.setString(1, sourceKey);
            statement.setString(2, fingerprint);
            statement.setLong(3, asOf.value().toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReceiptHeader(
                        new ConfirmationReceiptId(resultSet.getString("receipt_id")),
                        resultSet.getInt("expected_artifacts"),
                        resultSet.getLong("row_count")));
            }
        }
    }

    private List<ConfirmationReceiptArtifact> loadArtifacts(Connection connection,
                                                            ReceiptHeader header) throws SQLException {
        var artifacts = new ArrayList<ConfirmationReceiptArtifact>();
        long totalRows = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT artifact, row_count
                FROM confirmation_receipt_artifact
                WHERE receipt_id = ?
                ORDER BY artifact
                """)) {
            statement.setString(1, header.id().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String artifact = resultSet.getString("artifact");
                    DataframeArtifactSchema schema = schemas.get(artifact);
                    if (schema == null) {
                        throw new IocExtractorException(
                                "Complete receipt references unknown artifact: " + artifact);
                    }
                    List<CanonicalRecordConfirmation> rows = loadRows(
                            connection, schema, header.id());
                    if (rows.size() != resultSet.getInt("row_count")) {
                        throw new IocExtractorException(
                                "Complete receipt row count mismatch for artifact: " + artifact);
                    }
                    totalRows = Math.addExact(totalRows, rows.size());
                    artifacts.add(new ConfirmationReceiptArtifact(
                            artifact, publicHeader(schema), rows));
                }
            }
        }
        if (artifacts.size() != header.expectedArtifacts() || totalRows != header.rowCount()) {
            throw new IocExtractorException("Complete receipt structural totals do not match its header");
        }
        return List.copyOf(artifacts);
    }

    private List<CanonicalRecordConfirmation> loadRows(Connection connection,
                                                       DataframeArtifactSchema schema,
                                                       ConfirmationReceiptId receiptId) throws SQLException {
        List<String> businessColumns = publicHeader(schema).stream()
                .filter(column -> !"id".equals(column))
                .toList();
        String sql = "SELECT " + quote("row_key")
                + (businessColumns.isEmpty() ? "" : ", " + joinedQuoted(businessColumns))
                + " FROM " + quote(schema.artifactName() + "_receipt_rows")
                + " WHERE " + quote("receipt_id") + " = ? ORDER BY " + quote("ordinal");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, receiptId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                var rows = new ArrayList<CanonicalRecordConfirmation>();
                while (resultSet.next()) {
                    var values = new LinkedHashMap<String, String>();
                    for (DataframeColumn column : schema.columns()) {
                        values.put(column.name(), "id".equals(column.name())
                                ? null : resultSet.getString(column.name()));
                    }
                    Optional<String> idColumn = publicHeader(schema).contains("id")
                            ? Optional.of("id") : Optional.empty();
                    rows.add(new CanonicalRecordConfirmation(
                            new ArtifactRowKey(resultSet.getString("row_key")),
                            new PreparedArtifactRow(ArtifactRow.ordered(values), idColumn)));
                }
                return List.copyOf(rows);
            }
        }
    }

    private int purgeReceipts(Connection connection,
                              EffectiveTime asOf,
                              int batchSize) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM confirmation_receipt
                WHERE receipt_id IN (
                    SELECT r.receipt_id
                    FROM confirmation_receipt r
                    LEFT JOIN confirmation_receipt_artifact a ON a.receipt_id = r.receipt_id
                    WHERE (r.state = 'COMPLETE' AND r.purge_after_ms <= ?)
                       OR (r.state = 'STAGING' AND a.staged_at_ms <= ?)
                    GROUP BY r.receipt_id
                    ORDER BY COALESCE(r.purge_after_ms, MIN(a.staged_at_ms)), r.receipt_id
                    LIMIT ?
                )
                """)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            statement.setLong(2, asOf.value().minus(stagingRetention).toEpochMilli());
            statement.setInt(3, batchSize);
            return statement.executeUpdate();
        }
    }

    private void purgeObservations(Connection connection,
                                   EffectiveTime asOf,
                                   int batchSize) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM canonical_observation
                WHERE observation_id IN (
                    SELECT observation_id
                    FROM canonical_observation
                    WHERE state = 'TERMINAL' AND purge_after_ms <= ?
                    ORDER BY purge_after_ms, observation_id
                    LIMIT ?
                )
                """)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            statement.setInt(2, batchSize);
            statement.executeUpdate();
        }
    }

    private boolean hasExpiredReceipts(Connection connection, EffectiveTime asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM confirmation_receipt r
                LEFT JOIN confirmation_receipt_artifact a ON a.receipt_id = r.receipt_id
                WHERE (r.state = 'COMPLETE' AND r.purge_after_ms <= ?)
                   OR (r.state = 'STAGING' AND a.staged_at_ms <= ?)
                LIMIT 1
                """)) {
            statement.setLong(1, asOf.value().toEpochMilli());
            statement.setLong(2, asOf.value().minus(stagingRetention).toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean isTerminal(Connection connection, ObservationId observationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM canonical_observation WHERE observation_id = ?")) {
            statement.setString(1, observationId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "TERMINAL".equals(resultSet.getString(1));
            }
        }
    }

    private List<String> publicHeader(DataframeArtifactSchema schema) {
        return schema.columns().stream().map(DataframeColumn::name).toList();
    }

    private String joinedQuoted(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(java.util.stream.Collectors.joining(", "));
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record ReceiptHeader(ConfirmationReceiptId id, int expectedArtifacts, long rowCount) {
    }
}
