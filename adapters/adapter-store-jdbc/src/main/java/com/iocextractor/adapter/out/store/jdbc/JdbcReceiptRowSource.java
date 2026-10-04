package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;
import com.iocextractor.application.artifact.lifecycle.CanonicalRecordConfirmation;
import com.iocextractor.application.artifact.lifecycle.ConfirmationReceiptId;
import com.iocextractor.application.observation.OccurrencePosition;
import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.common.IocExtractorException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/** Repeatable receipt rows with one row's positions in memory and no second writer connection. */
final class JdbcReceiptRowSource implements JdbcConnectionRowSource<CanonicalRecordConfirmation> {
    private final DataSource dataSource;
    private final DataframeArtifactSchema schema;
    private final ConfirmationReceiptId receipt;
    private final int size;

    JdbcReceiptRowSource(DataSource dataSource, DataframeArtifactSchema schema, ConfirmationReceiptId receipt, int size) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.receipt = receipt;
        this.size = size;
        if (size < 0) { throw new IocExtractorException("Negative complete receipt row count"); }
    }

    public int size() { return size; }

    void validateCount(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM confirmation_receipt WHERE receipt_id=? AND state='COMPLETE'")) {
            statement.setString(1, receipt.value());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new IocExtractorException("Complete receipt disappeared before replay"); }
            }
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table() + " WHERE receipt_id=?")) {
            statement.setString(1, receipt.value());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != size) {
                    throw new IocExtractorException("Complete receipt row count mismatch for artifact: " + schema.artifactName());
                }
            }
        }
    }

    public RowCursor<CanonicalRecordConfirmation> open() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return cursor(connection, true);
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw new IocExtractorException("Cannot open receipt cursor", failure);
        }
    }

    public RowCursor<CanonicalRecordConfirmation> open(Connection connection) { return cursor(connection, false); }

    private RowCursor<CanonicalRecordConfirmation> cursor(Connection connection, boolean ownsConnection) {
        PreparedStatement statement = null;
        PreparedStatement positions = null;
        try {
            validateCount(connection);
            // Identifiers come exclusively from the admitted dataframe schema.
            String columns = schema.columns().stream().filter(column -> !"id".equals(column.name()))
                    .map(column -> ",\"" + DataframeColumn.requireSqlIdentifier(column.name(), "column") + "\"")
                    .collect(java.util.stream.Collectors.joining());
            statement = connection.prepareStatement("SELECT ordinal,row_key" + columns + " FROM " + table()
                    + " WHERE receipt_id=? ORDER BY ordinal");
            statement.setString(1, receipt.value());
            ResultSet rows = statement.executeQuery();
            positions = connection.prepareStatement("SELECT field_name,occurrence_position FROM confirmation_receipt_field_position WHERE receipt_id=? AND artifact=? AND ordinal=? ORDER BY field_name");
            positions.setString(1, receipt.value());
            positions.setString(2, schema.artifactName());
            return new ReceiptCursor(connection, ownsConnection, statement, positions, rows);
        } catch (SQLException | RuntimeException failure) {
            closeOnFailure(positions, failure);
            closeOnFailure(statement, failure);
            throw new IocExtractorException("Cannot open typed receipt rows", failure);
        }
    }

    private String table() {
        return "\"" + DataframeColumn.requireSqlIdentifier(schema.artifactName() + "_receipt_rows", "receipt table") + "\"";
    }

    private static void closeOnFailure(AutoCloseable resource, Exception failure) {
        if (resource != null) {
            try { resource.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
        }
    }

    private final class ReceiptCursor implements RowCursor<CanonicalRecordConfirmation> {
        private final Connection connection;
        private final boolean ownsConnection;
        private final PreparedStatement statement;
        private final PreparedStatement positions;
        private final ResultSet rows;
        private CanonicalRecordConfirmation current;
        private int read;
        private boolean closed;

        private ReceiptCursor(Connection connection, boolean ownsConnection, PreparedStatement statement,
                PreparedStatement positions, ResultSet rows) {
            this.connection = connection; this.ownsConnection = ownsConnection;
            this.statement = statement; this.positions = positions; this.rows = rows;
        }

        public boolean next() {
            if (closed) { throw new IllegalStateException("Receipt cursor is closed"); }
            if (Thread.currentThread().isInterrupted()) { throw new IocExtractorException("Receipt read interrupted"); }
            try {
                if (!rows.next()) {
                    current = null;
                    if (read != size) { throw new IocExtractorException("Receipt rows disappeared during replay"); }
                    return false;
                }
                read++;
                var values = new LinkedHashMap<String, String>();
                for (var column : schema.columns()) {
                    values.put(column.name(), "id".equals(column.name()) ? null : rows.getString(column.name()));
                }
                Map<String, OccurrencePosition> fields = new LinkedHashMap<>();
                positions.setInt(3, rows.getInt("ordinal"));
                try (var fieldRows = positions.executeQuery()) {
                    while (fieldRows.next()) {
                        fields.put(fieldRows.getString(1), new OccurrencePosition(fieldRows.getLong(2)));
                    }
                }
                var id = values.containsKey("id") ? Optional.of("id") : Optional.<String>empty();
                current = new CanonicalRecordConfirmation(new ArtifactRowKey(rows.getString("row_key")),
                        new PreparedArtifactRow(ArtifactRow.ordered(values), id, fields));
                return true;
            } catch (SQLException failure) { throw new IocExtractorException("Cannot read typed receipt row", failure); }
        }

        public CanonicalRecordConfirmation value() { return java.util.Objects.requireNonNull(current, "current receipt row"); }

        public void close() {
            if (closed) { return; }
            closed = true; current = null;
            SQLException failure = null;
            try { positions.close(); } catch (SQLException error) { failure = error; }
            try { statement.close(); } catch (SQLException error) {
                if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
            }
            if (ownsConnection) {
                try { connection.rollback(); } catch (SQLException error) {
                    if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
                }
                try { connection.close(); } catch (SQLException error) {
                    if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
                }
            }
            if (failure != null) { throw new IocExtractorException("Cannot close typed receipt cursor", failure); }
        }
    }
}
