package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** One temporary projection row on a borrowed writer connection; never a second database or pool. */
final class JdbcDirectProjectionState implements AutoCloseable {
    private final Connection connection;
    private final JdbcPublicationTable table;
    private final List<Object> previous;

    JdbcDirectProjectionState(Connection connection, JdbcTypedPublicationStore store, String artifact, int byteLimit) throws SQLException {
        this.connection = connection;
        table = store.tables().stream().filter(candidate -> candidate.name().equals("artifact_projection_state"))
                .findFirst().orElseThrow(() -> new SQLException("Missing projection publication family"));
        // Inspect SQLite byte lengths before copying or materializing a possibly oversized row.
        try (var statement = connection.prepareStatement("SELECT LENGTH(CAST(artifact AS BLOB))"
                + "+COALESCE(LENGTH(CAST(last_error_code AS BLOB)),0) FROM main.artifact_projection_state WHERE artifact=?")) {
            statement.setString(1, artifact);
            try (var rows = statement.executeQuery()) {
                if (rows.next() && rows.getLong(1) > byteLimit - 2048L) {
                    throw new SQLException("Projection control payload exceeds direct byte admission");
                }
            }
        }
        // The explicit name avoids shadowing the committed view used by other protocol operations.
        JdbcPublicationTable.execute(connection, "CREATE TEMP TABLE direct_projection_state AS SELECT artifact,required_generation,"
                + "projected_generation,requested_at_ms,projected_at_ms,last_error_code FROM main.artifact_projection_state WHERE 0");
        try {
            try (var statement = connection.prepareStatement("INSERT INTO temp.direct_projection_state SELECT artifact,required_generation,"
                    + "projected_generation,requested_at_ms,projected_at_ms,last_error_code FROM main.artifact_projection_state WHERE artifact=?")) {
                statement.setString(1, artifact);
                if (statement.executeUpdate() > 1) { throw new SQLException("Projection control exceeded one row"); }
            }
            previous = values();
        } catch (SQLException | RuntimeException | Error failure) {
            try { close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    JdbcProjectionMutation mutation() {
        return new JdbcProjectionMutation(JdbcClient.create(new SingleConnectionDataSource(connection, true)),
                JdbcProjectionMutation.DIRECT_TABLE);
    }

    JdbcTypedPublicationChunk effects(JdbcTypedPublicationStore store, int byteLimit) throws SQLException {
        var chunk = new JdbcTypedPublicationChunk(store.artifacts(), byteLimit);
        var updated = values();
        if (!updated.equals(previous)) {
            chunk.add(new JdbcPrivateChanges.Effect(table, updated, previous, false, false), false);
        }
        return chunk;
    }

    private List<Object> values() throws SQLException {
        // Reuse the kernel's statement owner; this scope closes its sole lease with no later reuse.
        try (var statements = new JdbcStatementScope(connection)) {
            var statement = statements.borrow("SELECT artifact,required_generation,projected_generation,requested_at_ms,projected_at_ms,last_error_code"
                    + " FROM temp.direct_projection_state").statement();
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) { return List.of(); }
                var result = new ArrayList<Object>(table.columns().size());
                // Preserve catalog order by name; unsupported added columns fail closed in the driver.
                for (String column : table.columns()) { result.add(rows.getObject(column)); }
                // Constructor admitted at most one row; the two registered UPDATEs cannot change cardinality.
                return result;
            }
        }
    }

    /** Drops owned temporary state before returning the connection to its pool. */
    @Override public void close() throws SQLException {
        JdbcPublicationTable.execute(connection, "DROP TABLE temp.direct_projection_state");
    }
}
