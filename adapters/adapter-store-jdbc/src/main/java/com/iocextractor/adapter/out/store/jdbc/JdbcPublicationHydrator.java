package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.placeholders;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Streams explicit selective committed queries into native private tables; no database copy or table diff. */
final class JdbcPublicationHydrator {
    private final Connection base;
    private final Connection working;
    private final Map<String, JdbcPublicationTable> tables;
    private final Runnable quota;
    private final DocumentPreparationLimits limits;

    JdbcPublicationHydrator(Connection base, Connection working, List<JdbcPublicationTable> tables, Runnable quota, DocumentPreparationLimits limits) {
        this.base = Objects.requireNonNull(base, "base");
        this.working = Objects.requireNonNull(working, "working");
        this.tables = tables.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                JdbcPublicationTable::name, java.util.function.Function.identity()));
        this.quota = Objects.requireNonNull(quota, "quota");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** SQL comes only from the adapter's query catalog; operator values always use bound parameters. */
    long copy(String tableName, String predicate, List<Object> parameters) throws SQLException {
        var table = tables.get(tableName);
        if (table == null) { table = JdbcPublicationTable.inspect(base, tableName); }
        return copy(table, predicate, parameters);
    }

    private long copy(JdbcPublicationTable table, String predicate, List<Object> parameters) throws SQLException {
        if (base.getAutoCommit() || working.getAutoCommit()) {
            throw new SQLException("Selective hydration requires owned source and private snapshots");
        }
        String selectSql = "SELECT " + sizeGuard(table) + "," + table.selectColumns("b") + " FROM " + quote(table.name())
                + " b WHERE " + predicate;
        String insertSql = "INSERT OR IGNORE INTO " + quote(table.name()) + "(" + joinedQuoted(table.columns())
                + ") VALUES(" + placeholders(table.columns().size()) + ")";
        long count = 0;
        long batchBytes = 0;
        int byteLimit = JdbcCanonicalWorkspaceLimits.chunkBytes(limits);
        try (var select = base.prepareStatement(selectSql); var insert = working.prepareStatement(insertSql)) {
            JdbcSql.bind(select, parameters);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    if (Thread.currentThread().isInterrupted()) { throw new SQLException("Selective hydration interrupted"); }
                    if (rows.getInt(1) != 0) { throw new SQLException("Committed native row exceeds canonical preparation limits"); }
                    var values = new java.util.ArrayList<Object>(table.columns().size());
                    long bytes = readValues(rows, table.columns().size(), values);
                    if (batchBytes > 0 && batchBytes + bytes > byteLimit) {
                        insert.executeBatch(); insert.clearBatch(); quota.run(); batchBytes = 0;
                    }
                    JdbcSql.bind(insert, values);
                    insert.addBatch();
                    batchBytes += bytes;
                    if (++count % 256 == 0 || batchBytes >= byteLimit) {
                        insert.executeBatch(); insert.clearBatch(); quota.run(); batchBytes = 0;
                    }
                }
            }
            insert.executeBatch();
        }
        quota.run();
        return count;
    }

    /** Fills only the caller's current row, returning its size without encoding the payload twice. */
    private long readValues(ResultSet rows, int columns, List<Object> values) throws SQLException {
        long bytes = 0;
        for (int column = 1; column <= columns; column++) {
            Object value = rows.getObject(column + 1);
            long field = JdbcPublicationChunkDigest.framedBytes(value);
            bytes = Math.addExact(bytes, field);
            if (field > limits.maximumFieldBytes() || bytes > limits.maximumRowBytes()) {
                throw new SQLException("Committed native row exceeds canonical preparation limits");
            }
            values.add(value);
        }
        return bytes;
    }

    /** SQLite checks byte sizes before the driver materializes an oversized text/blob into the JVM. */
    private String sizeGuard(JdbcPublicationTable table) {
        var sizes = table.columns().stream().map(column -> "(5+COALESCE(length(CAST(b."
                + quote(column) + " AS BLOB)),0))").toList();
        String fields = sizes.stream().map(size -> size + ">" + limits.maximumFieldBytes())
                .collect(java.util.stream.Collectors.joining(" OR "));
        return "CASE WHEN " + fields + " OR (" + String.join("+", sizes) + ")>" + limits.maximumRowBytes() + " THEN 1 ELSE 0 END";
    }
}
