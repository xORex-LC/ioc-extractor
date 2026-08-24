package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.CanonicalArtifactConfirmation;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.bind;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.epochMillis;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.placeholders;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Stages typed prepared rows and publishes only structurally complete receipts. */
final class JdbcConfirmationReceiptWriter {

    private final Map<String, DataframeArtifactSchema> schemas;

    JdbcConfirmationReceiptWriter(Map<String, DataframeArtifactSchema> schemas) {
        this.schemas = schemas;
    }

    void stageAndPublishIfComplete(Connection connection,
                                   DataframeArtifactSchema schema,
                                   CanonicalArtifactConfirmation confirmation,
                                   EffectiveTime asOf) throws SQLException {
        ensureHeader(connection, confirmation);
        stageRowsAndMarker(connection, schema, confirmation, asOf);
        publishIfComplete(connection, confirmation, asOf);
    }

    private void stageRowsAndMarker(Connection connection,
                                    DataframeArtifactSchema schema,
                                    CanonicalArtifactConfirmation confirmation,
                                    EffectiveTime asOf) throws SQLException {
        String receiptId = confirmation.receipt().id().value();
        List<String> businessColumns = publicHeader(schema).stream()
                .filter(column -> !"id".equals(column))
                .toList();
        List<String> columns = new ArrayList<>(List.of(
                "receipt_id", "ordinal", "row_key", "source_key", "observed_at_ms"));
        columns.addAll(businessColumns);
        String sql = "INSERT INTO " + quote(schema.artifactName() + "_receipt_rows") + " ("
                + joinedQuoted(columns) + ") VALUES (" + placeholders(columns.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int ordinal = 0;
            for (CanonicalRecordConfirmation record : confirmation.records()) {
                List<Object> values = new ArrayList<>(List.of(
                        receiptId, ordinal, record.rowKey().value(), confirmation.sourceKey(), epochMillis(asOf)));
                for (String column : businessColumns) {
                    values.add(record.preparedRow().template().value(column));
                }
                bind(statement, values);
                statement.addBatch();
                ordinal++;
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO confirmation_receipt_artifact(receipt_id, artifact, row_count, staged_at_ms)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, receiptId);
            statement.setString(2, schema.artifactName());
            statement.setInt(3, confirmation.records().size());
            statement.setLong(4, epochMillis(asOf));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE confirmation_receipt
                SET row_count = row_count + ?
                WHERE receipt_id = ? AND state = 'STAGING'
                """)) {
            statement.setInt(1, confirmation.records().size());
            statement.setString(2, receiptId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Confirmation receipt is not writable");
            }
        }
    }

    private void ensureHeader(Connection connection,
                              CanonicalArtifactConfirmation confirmation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO confirmation_receipt(
                    receipt_id, source_key, processing_policy_fingerprint,
                    state, expected_artifacts, row_count)
                VALUES (?, ?, ?, 'STAGING', ?, 0)
                ON CONFLICT(receipt_id) DO NOTHING
                """)) {
            statement.setString(1, confirmation.receipt().id().value());
            statement.setString(2, confirmation.sourceKey());
            statement.setString(3, confirmation.receipt().processingPolicyFingerprint());
            statement.setInt(4, confirmation.receipt().expectedArtifacts());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_key, processing_policy_fingerprint, expected_artifacts, state
                FROM confirmation_receipt
                WHERE receipt_id = ?
                """)) {
            statement.setString(1, confirmation.receipt().id().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !confirmation.sourceKey().equals(resultSet.getString("source_key"))
                        || !confirmation.receipt().processingPolicyFingerprint()
                        .equals(resultSet.getString("processing_policy_fingerprint"))
                        || confirmation.receipt().expectedArtifacts() != resultSet.getInt("expected_artifacts")
                        || !"STAGING".equals(resultSet.getString("state"))) {
                    throw new IocExtractorException("Confirmation receipt identity is not writable");
                }
            }
        }
    }

    private void publishIfComplete(Connection connection,
                                   CanonicalArtifactConfirmation confirmation,
                                   EffectiveTime asOf) throws SQLException {
        String receiptId = confirmation.receipt().id().value();
        ReceiptTotals totals = receiptTotals(connection, receiptId);
        if (totals.markerCount() < confirmation.receipt().expectedArtifacts()) {
            return;
        }
        if (totals.markerCount() != confirmation.receipt().expectedArtifacts()) {
            throw new IocExtractorException("Confirmation receipt has too many artifact markers");
        }
        if (totals.markerRows() != receiptHeaderRows(connection, receiptId)) {
            throw new IocExtractorException("Confirmation receipt row total does not match markers");
        }
        validateTypedRows(connection, receiptId);
        long purgeAfter = asOf.value().plus(confirmation.receipt().retention()).toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE confirmation_receipt
                SET state = 'COMPLETE', completed_at_ms = ?, purge_after_ms = ?
                WHERE receipt_id = ? AND state = 'STAGING'
                """)) {
            statement.setLong(1, epochMillis(asOf));
            statement.setLong(2, purgeAfter);
            statement.setString(3, receiptId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Confirmation receipt publication lost its staging state");
            }
        }
    }

    private ReceiptTotals receiptTotals(Connection connection, String receiptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS marker_count, COALESCE(SUM(row_count), 0) AS marker_rows
                FROM confirmation_receipt_artifact
                WHERE receipt_id = ?
                """)) {
            statement.setString(1, receiptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new ReceiptTotals(
                        resultSet.getInt("marker_count"), resultSet.getLong("marker_rows"));
            }
        }
    }

    private long receiptHeaderRows(Connection connection, String receiptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT row_count FROM confirmation_receipt WHERE receipt_id = ?")) {
            statement.setString(1, receiptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IocExtractorException("Confirmation receipt header disappeared");
                }
                return resultSet.getLong("row_count");
            }
        }
    }

    private void validateTypedRows(Connection connection, String receiptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT artifact, row_count
                FROM confirmation_receipt_artifact
                WHERE receipt_id = ?
                ORDER BY artifact
                """)) {
            statement.setString(1, receiptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String artifact = resultSet.getString("artifact");
                    if (!schemas.containsKey(artifact)) {
                        throw new IocExtractorException("Receipt references an unknown artifact: " + artifact);
                    }
                    if (countTypedRows(connection, artifact, receiptId) != resultSet.getLong("row_count")) {
                        throw new IocExtractorException(
                                "Typed confirmation receipt row count does not match marker: " + artifact);
                    }
                }
            }
        }
    }

    private long countTypedRows(Connection connection, String artifact, String receiptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + quote(artifact + "_receipt_rows")
                        + " WHERE " + quote("receipt_id") + " = ?")) {
            statement.setString(1, receiptId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private List<String> publicHeader(DataframeArtifactSchema schema) {
        return schema.columns().stream().map(DataframeColumn::name).toList();
    }

    private record ReceiptTotals(int markerCount, long markerRows) {
    }
}
