package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.common.IocExtractorException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
        selected.addAll(publicHeader(schema).stream().map(this::quote).toList());
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
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + quote(schema.artifactName()) + " WHERE " + quote("id") + " = ?")) {
            statement.setLong(1, rowId);
            if (statement.executeUpdate() != 1) {
                throw new IocExtractorException("Due lifecycle disappeared before deletion");
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

    private String joinedQuoted(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    private String quote(String identifier) {
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }
}
