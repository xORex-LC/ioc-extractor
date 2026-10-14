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

/** Two ordered cursors share one SQL snapshot; only the current row's positions are retained. */
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
            positions = connection.prepareStatement("SELECT ordinal,field_name,occurrence_position FROM confirmation_receipt_field_position WHERE receipt_id=? AND artifact=? ORDER BY ordinal,field_name");
            positions.setString(1, receipt.value());
            positions.setString(2, schema.artifactName());
            return new ReceiptCursor(connection, ownsConnection, statement, positions, rows, positions.executeQuery());
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
        private final ResultSet fieldRows;
        private boolean hasPosition;
        private CanonicalRecordConfirmation current;
        private int read;
        private boolean closed;

        private ReceiptCursor(Connection connection, boolean ownsConnection, PreparedStatement statement,
                PreparedStatement positions, ResultSet rows, ResultSet fieldRows) throws SQLException {
            this.connection = connection; this.ownsConnection = ownsConnection;
            this.statement = statement; this.positions = positions; this.rows = rows;
            this.fieldRows = fieldRows; this.hasPosition = fieldRows.next();
        }

        public boolean next() {
            if (closed) { throw new IllegalStateException("Receipt cursor is closed"); }
            if (Thread.currentThread().isInterrupted()) { throw new IocExtractorException("Receipt read interrupted"); }
            try {
                if (!rows.next()) {
                    current = null;
                    if (hasPosition) { throw new IocExtractorException("Receipt positions have no matching row"); }
                    if (read != size) { throw new IocExtractorException("Receipt rows disappeared during replay"); }
                    return false;
                }
                read++;
                var values = new LinkedHashMap<String, String>();
                for (var column : schema.columns()) {
                    values.put(column.name(), "id".equals(column.name()) ? null : rows.getString(column.name()));
                }
                Map<String, OccurrencePosition> fields = readFieldPositions(rows.getInt("ordinal"));
                var id = values.containsKey("id") ? Optional.of("id") : Optional.<String>empty();
                current = new CanonicalRecordConfirmation(new ArtifactRowKey(rows.getString("row_key")),
                        new PreparedArtifactRow(ArtifactRow.ordered(values), id, fields));
                return true;
            } catch (SQLException failure) { throw new IocExtractorException("Cannot read typed receipt row", failure); }
        }

        /** Consumes only this row's ordered positions within the same pinned SQLite snapshot. */
        private Map<String, OccurrencePosition> readFieldPositions(int ordinal) throws SQLException {
            Map<String, OccurrencePosition> fields = new LinkedHashMap<>();
            if (hasPosition && fieldRows.getInt(1) < ordinal) {
                throw new IocExtractorException("Receipt positions have no matching row");
            }
            while (hasPosition && fieldRows.getInt(1) == ordinal) {
                String field = fieldRows.getString(2);
                if (fields.put(field, new OccurrencePosition(fieldRows.getLong(3))) != null) {
                    throw new IocExtractorException("Duplicate receipt field position");
                }
                hasPosition = fieldRows.next();
            }
            return fields;
        }

        public CanonicalRecordConfirmation value() { return java.util.Objects.requireNonNull(current, "current receipt row"); }

        public void close() {
            if (closed) { return; }
            closed = true; current = null;
            SQLException failure = null;
            try { positions.close(); } catch (SQLException error) { failure = error; }
            try { statement.close(); } catch (SQLException error) {
                failure = closingFailure(failure, error);
            }
            if (ownsConnection) {
                try { connection.rollback(); } catch (SQLException error) {
                    failure = closingFailure(failure, error);
                }
                try { connection.close(); } catch (SQLException error) {
                    failure = closingFailure(failure, error);
                }
            }
            if (failure != null) { throw new IocExtractorException("Cannot close typed receipt cursor", failure); }
        }
    }

    private static SQLException closingFailure(SQLException failure, SQLException error) {
        if (failure == null) { return error; }
        failure.addSuppressed(error);
        return failure;
    }
}
