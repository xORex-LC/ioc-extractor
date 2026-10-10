package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.placeholders;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Bounded typed transport; one journal digest covers the actual values, NULL, flags and attempt identity. */
final class JdbcTypedPublicationChunk {
    record Row(JdbcPrivateChanges.Effect effect, String address, String timeColumns, boolean artifact) { }
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> artifacts;
    private long bytes;

    JdbcTypedPublicationChunk(Set<String> artifacts) { this.artifacts = Set.copyOf(artifacts); }

    /** Returns false before retaining a row that belongs in the next chunk. */
    boolean add(JdbcPrivateChanges.Effect effect, boolean publicationTime) throws SQLException {
        var identity = new JdbcPublicationChunkDigest();
        for (String key : effect.table().keys()) { identity.field(effect.value(key)); }
        var row = new Row(effect, identity.finish(), publicationTime ? JdbcPublicationTimes.changed(effect) : "",
                artifacts.contains(effect.table().name()));
        long size = framedBytes(row);
        if (size > 1024 * 1024) { throw new SQLException("One typed publication row exceeds chunk byte limit"); }
        if (rows.size() == 256 || bytes + size > 1024 * 1024) { return false; }
        rows.add(row); bytes += size;
        return true;
    }

    int size() { return rows.size(); }
    boolean isEmpty() { return rows.isEmpty(); }

    /** Drained migration copies literal facts, without confirmation, renewal or runtime transfer journals. */
    void copyMigrationRows(Connection connection, JdbcPublicationProtocol.Attempt attempt) throws SQLException {
        for (var row : rows) { insert(connection, attempt, row); }
    }

    void append(Connection connection, JdbcPublicationProtocol protocol, JdbcPublicationProtocol.Attempt attempt,
                String scope, int ordinal) throws SQLException {
        var digest = new JdbcPublicationChunkDigest();
        for (var row : rows) {
            digest.row();
            digest.field(row.effect().table().name());
            for (String column : row.effect().table().columns()) { digest.field(column); }
            for (Object value : row.effect().values()) { digest.field(value); }
            digest.field(row.address()); digest.field(row.effect().deleted()); digest.field(row.timeColumns());
            digest.field(attempt.unitId()); digest.field(attempt.fence()); digest.field(attempt.stamp());
        }
        String checksum = digest.finish();
        var pointers = rows.stream().map(row -> new JdbcPublicationProtocol.Fact("typed", scope,
                row.effect().table().name() + ":" + row.address(), checksum,
                anchored(row) ? attempt.unitId() : null)).toList();
        if (protocol.append(connection, attempt, ordinal, pointers)) {
            for (var row : rows) { insert(connection, attempt, row); }
        }
    }

    private void insert(Connection connection, JdbcPublicationProtocol.Attempt attempt, Row row) throws SQLException {
        JdbcPublicationTable table = row.effect().table();
        try (var statement = connection.prepareStatement("INSERT INTO " + quote(table.versions()) + "("
                + joinedQuoted(table.columns())
                + ",unit_id,fence,stamp,address,deleted,publication_columns,absolute_deadline,deadline_anchor) VALUES("
                + placeholders(table.columns().size() + 8) + ")")) {
            int index = 1;
            for (Object value : row.effect().values()) { statement.setObject(index++, value); }
            statement.setLong(index++, attempt.unitId()); statement.setLong(index++, attempt.fence());
            statement.setLong(index++, attempt.stamp()); statement.setString(index++, row.address());
            statement.setInt(index++, row.effect().deleted() ? 1 : 0); statement.setString(index++, row.timeColumns());
            statement.setObject(index++, row.artifact() && !anchored(row) ? row.effect().value("_valid_until_epoch_ms") : null);
            statement.setObject(index, anchored(row) ? attempt.unitId() : null);
            statement.executeUpdate();
        }
    }

    private static boolean anchored(Row row) {
        return row.artifact() && !row.effect().deleted() && row.timeColumns().contains(",_valid_until_epoch_ms,");
    }

    private static long framedBytes(Row row) throws SQLException {
        // Include fixed attempt fields and all names/flags used by the checksum, before retaining payload.
        long bytes = 128 + encodedSize(row.effect().table().name()) + encodedSize(row.address())
                + encodedSize(row.timeColumns());
        for (String column : row.effect().table().columns()) { bytes = Math.addExact(bytes, encodedSize(column)); }
        for (Object value : row.effect().values()) { bytes = Math.addExact(bytes, encodedSize(value)); }
        return bytes;
    }

    private static long encodedSize(Object value) throws SQLException {
        return JdbcPublicationChunkDigest.framedBytes(value);
    }
}
