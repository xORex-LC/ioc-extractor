package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.artifact.lifecycle.LifecycleId;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Atomic typed snapshot, compact provenance copy and active-row deletion. */
final class JdbcLifecycleArchive {

    void archiveAndDelete(Connection connection,
                          DataframeArtifactSchema schema,
                          long rowId,
                          EffectiveTime closedAt) throws SQLException {
        List<String> historyColumns = new ArrayList<>(List.of(
                "former_row_id", "row_key", "_lifecycle_id",
                "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms",
                "_valid_until_epoch_ms", "closed_at_epoch_ms", "close_reason"));
        historyColumns.addAll(publicHeader(schema));

        List<String> selected = new ArrayList<>(List.of(
                quote("id"), quote("row_key"), quote("_lifecycle_id"),
                quote("_first_confirmed_at_epoch_ms"), quote("_last_confirmed_at_epoch_ms"),
                quote("_valid_until_epoch_ms"), "?", "?"));
        selected.addAll(publicHeader(schema).stream().map(JdbcSql::quote).toList());
        String sql = "INSERT INTO " + quote(schema.artifactName() + "_history") + " ("
                + joinedQuoted(historyColumns) + ") SELECT " + String.join(", ", selected)
                + " FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, closedAt.value().toEpochMilli());
            statement.setString(2, "EXPIRED");
            statement.setLong(3, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Due lifecycle disappeared during archival");
            }
        }
        long historyId = lastInsertId(connection);
        copyHistorySources(connection, schema.artifactName(), rowId, historyId);
        archiveFieldOrigins(connection, schema.artifactName(), rowId, closedAt);
        deleteMatchAliases(connection, schema.artifactName(), rowId);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?")) {
            statement.setLong(1, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Due lifecycle disappeared before deletion");
            }
        }
    }

    private void archiveFieldOrigins(Connection connection,
                                     String artifact,
                                     long rowId,
                                     EffectiveTime closedAt) throws SQLException {
        String insertSql = """
                INSERT INTO canonical_lifecycle_field_origin_history(
                    artifact, lifecycle_id, field_name, admission_order,
                    occurrence_position, occurrence_id, archived_at_ms)
                SELECT origin.artifact, origin.lifecycle_id, origin.field_name,
                       origin.admission_order, origin.occurrence_position,
                       origin.occurrence_id, ?
                FROM canonical_lifecycle_field_origin origin
                JOIN """ + quote(artifact) + " active ON active." + quote("_lifecycle_id")
                + " = origin.lifecycle_id WHERE origin.artifact = ? AND active."
                + quote("id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setLong(1, closedAt.value().toEpochMilli());
            statement.setString(2, artifact);
            statement.setLong(3, rowId);
            statement.executeUpdate();
        }
        String deleteSql = """
                DELETE FROM canonical_lifecycle_field_origin
                WHERE artifact = ? AND lifecycle_id = (
                    SELECT """ + quote("_lifecycle_id") + " FROM " + quote(artifact)
                + " WHERE " + quote("id") + " = ?)";
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setString(1, artifact);
            statement.setLong(2, rowId);
            statement.executeUpdate();
        }
    }

    private void deleteMatchAliases(Connection connection, String artifact, long rowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM canonical_match_alias
                WHERE artifact = ? AND canonical_row_id = ?
                """)) {
            statement.setString(1, artifact);
            statement.setLong(2, rowId);
            statement.executeUpdate();
        }
    }

    void archiveLegacyAndDelete(Connection connection,
                                DataframeArtifactSchema schema,
                                long rowId,
                                LifecycleId lifecycleId,
                                EffectiveTime closedAt) throws SQLException {
        long closedAtMs = closedAt.value().toEpochMilli();
        long confirmedAtMs = Math.subtractExact(closedAtMs, 1L);
        List<String> historyColumns = new ArrayList<>(List.of(
                "former_row_id", "row_key", "_lifecycle_id",
                "_first_confirmed_at_epoch_ms", "_last_confirmed_at_epoch_ms",
                "_valid_until_epoch_ms", "closed_at_epoch_ms", "close_reason"));
        historyColumns.addAll(publicHeader(schema));

        List<String> selected = new ArrayList<>(List.of(
                quote("id"), quote("row_key"), "?", "?", "?", "?", "?", "?"));
        selected.addAll(publicHeader(schema).stream().map(JdbcSql::quote).toList());
        String sql = "INSERT INTO " + quote(schema.artifactName() + "_history") + " ("
                + joinedQuoted(historyColumns) + ") SELECT " + String.join(", ", selected)
                + " FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?"
                + " AND " + quote("_lifecycle_id") + " IS NULL"
                + " AND " + quote("_first_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_last_confirmed_at_epoch_ms") + " IS NULL"
                + " AND " + quote("_valid_until_epoch_ms") + " IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lifecycleId.value());
            statement.setLong(2, confirmedAtMs);
            statement.setLong(3, confirmedAtMs);
            statement.setLong(4, closedAtMs);
            statement.setLong(5, closedAtMs);
            statement.setString(6, "LEGACY_ACTIVATION");
            statement.setLong(7, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Legacy lifecycle disappeared during activation archival");
            }
        }
        long historyId = lastInsertId(connection);
        copyHistorySources(connection, schema.artifactName(), rowId, historyId);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?")) {
            statement.setLong(1, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Legacy lifecycle disappeared before activation deletion");
            }
        }
    }

    private void copyHistorySources(Connection connection,
                                    String artifact,
                                    long rowId,
                                    long historyId) throws SQLException {
        String sql = "INSERT INTO " + quote(artifact + "_history_sources") + " ("
                + joinedQuoted(List.of("history_id", "source_key", "first_seen_at", "last_seen_at", "occurrences"))
                + ") SELECT ?, " + joinedQuoted(List.of("source_key", "first_seen_at", "last_seen_at", "occurrences"))
                + " FROM " + quote(artifact + "_sources") + " WHERE " + quote("row_id") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, historyId);
            statement.setLong(2, rowId);
            statement.executeUpdate();
        }
    }

    private long lastInsertId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT last_insert_rowid()")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private List<String> publicHeader(DataframeArtifactSchema schema) {
        return schema.columns().stream().map(DataframeColumn::name).toList();
    }

}
