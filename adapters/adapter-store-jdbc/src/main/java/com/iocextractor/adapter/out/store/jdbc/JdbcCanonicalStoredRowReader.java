package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.common.IocExtractorException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Immutable row decoder shared by import planning and transaction-scoped mutations. */
final class JdbcCanonicalStoredRowReader {
    private final String artifact;
    private final List<String> publicColumns;
    private final String sql;

    JdbcCanonicalStoredRowReader(DataframeArtifactSchema schema) {
        this.artifact = schema.artifactName();
        this.publicColumns = schema.columns().stream().map(DataframeColumn::name).toList();
        this.sql = "SELECT " + quote("id") + ", " + quote("row_key") + ", "
                + quote("_lifecycle_id") + ", " + quote("_first_confirmed_at_epoch_ms") + ", "
                + quote("_last_confirmed_at_epoch_ms") + ", " + quote("_valid_until_epoch_ms")
                + (publicColumns.isEmpty() ? "" : ", " + joinedQuoted(publicColumns))
                + " FROM " + quote(artifact) + " WHERE " + quote("id") + " = ?";
    }

    String sql() {
        return sql;
    }

    JdbcCanonicalMutationEngine.StoredLifecycle load(PreparedStatement statement, long rowId)
            throws SQLException {
        statement.setLong(1, rowId);
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                throw new IocExtractorException("Canonical match alias points to a missing row");
            }
            long lifecycleId = requiredLong(rows, "_lifecycle_id");
            long firstConfirmed = requiredLong(rows, "_first_confirmed_at_epoch_ms");
            long lastConfirmed = requiredLong(rows, "_last_confirmed_at_epoch_ms");
            long validUntil = requiredLong(rows, "_valid_until_epoch_ms");
            if (lifecycleId <= 0 || firstConfirmed > lastConfirmed || lastConfirmed >= validUntil) {
                throw new IocExtractorException("Active lifecycle row has invalid ordered metadata: " + artifact);
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String column : publicColumns) {
                values.put(column, rows.getString(column));
            }
            return new JdbcCanonicalMutationEngine.StoredLifecycle(rowId, lifecycleId, validUntil,
                    new ArtifactRowKey(rows.getString("row_key")), ArtifactRow.ordered(values));
        }
    }

    private long requiredLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        if (rows.wasNull()) {
            throw new IocExtractorException("Active lifecycle row is missing required metadata: " + artifact);
        }
        return value;
    }
}
