package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import com.iocextractor.common.IocExtractorException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads immutable planned requests without a delivery-sized Java list. Each cursor closes
 * its statement and results; the promotion still owns the shared transaction connection.
 */
final class JdbcImportSlotRequests implements RowSource<PreferredExportSlotRequest> {
    private final Connection connection;
    private final String artifact;
    private final ImportExistingSlotPolicy policy;
    private final int size;

    JdbcImportSlotRequests(Connection connection, String artifact, ImportExistingSlotPolicy policy) throws SQLException {
        this.connection = connection;
        this.artifact = artifact;
        this.policy = policy;
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM temp.temp_import_plan WHERE artifact = ? AND requested_slot IS NOT NULL")) {
            statement.setString(1, artifact);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Import slot request count is missing"); }
                size = Math.toIntExact(rows.getLong(1));
            }
        }
    }

    @Override
    public int size() { return size; }

    @Override
    public RowCursor<PreferredExportSlotRequest> open() {
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement("""
                    SELECT applied_lifecycle_id, requested_slot, new_lifecycle
                    FROM temp.temp_import_plan WHERE artifact = ? AND requested_slot IS NOT NULL
                    ORDER BY source_row_number, branch_id
                    """);
            statement.setString(1, artifact);
            return new Cursor(statement, statement.executeQuery());
        } catch (SQLException | RuntimeException | Error failure) {
            if (statement != null) {
                try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            if (failure instanceof Error fatal) { throw fatal; }
            throw new IocExtractorException("Cannot open planned import slots", failure);
        }
    }

    private final class Cursor implements RowCursor<PreferredExportSlotRequest> {
        private final PreparedStatement statement;
        private final ResultSet rows;
        private PreferredExportSlotRequest current;

        private Cursor(PreparedStatement statement, ResultSet rows) { this.statement = statement; this.rows = rows; }

        @Override
        public boolean next() {
            try {
                current = rows.next() ? new PreferredExportSlotRequest(rows.getLong(1), rows.getLong(2),
                        rows.getInt(3) == 1, policy) : null;
                return current != null;
            } catch (SQLException failure) { throw new IocExtractorException("Cannot read planned import slots", failure); }
        }

        @Override
        public PreferredExportSlotRequest value() {
            if (current == null) { throw new IllegalStateException("Import slot cursor is not on a row"); }
            return current;
        }

        @Override
        public void close() {
            try (statement; rows) { current = null; }
            catch (SQLException failure) { throw new IocExtractorException("Cannot close planned import slots", failure); }
        }
    }
}
