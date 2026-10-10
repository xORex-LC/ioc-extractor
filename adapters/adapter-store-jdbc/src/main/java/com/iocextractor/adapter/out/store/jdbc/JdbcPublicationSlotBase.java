package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Minimal lifecycle membership overlay for the existing slot allocator, including own native mutations. */
final class JdbcPublicationSlotBase {
    private final Connection base;
    private final Connection working;
    private final Runnable quota;
    private final Map<String, String> views = new LinkedHashMap<>();
    private long minimumDeadline = Long.MAX_VALUE;

    JdbcPublicationSlotBase(Connection base, Connection working, Runnable quota) {
        this.base = base; this.working = working; this.quota = quota;
    }

    void hydrate(String artifact, long preparedUtc) throws SQLException {
        if (views.containsKey(artifact)) { return; }
        String headers = "publication_slot_base_" + artifact;
        String selected = "publication_slot_selected_" + artifact;
        String view = "publication_slot_view_" + artifact;
        execute(working, "CREATE TABLE " + quote(headers)
                + "(id INTEGER PRIMARY KEY,_lifecycle_id INTEGER NOT NULL UNIQUE,_valid_until_epoch_ms INTEGER NOT NULL)");
        execute(working, "CREATE TABLE " + quote(selected) + "(id INTEGER PRIMARY KEY)");
        stream(artifact, headers, preparedUtc);
        try (var select = base.prepareStatement("SELECT row_id FROM temp.publication_selected_row WHERE artifact=?");
             var insert = working.prepareStatement("INSERT INTO " + quote(selected) + " VALUES(?)")) {
            select.setString(1, artifact);
            try (var rows = select.executeQuery()) {
                while (rows.next()) { insert.setLong(1, rows.getLong(1)); insert.executeUpdate(); }
            }
        }
        execute(working, "CREATE VIEW " + quote(view) + " AS SELECT b.id,b._lifecycle_id,b._valid_until_epoch_ms"
                + " FROM " + quote(headers) + " b WHERE NOT EXISTS(SELECT 1 FROM " + quote(selected)
                + " s WHERE s.id=b.id) UNION ALL SELECT id,_lifecycle_id,_valid_until_epoch_ms FROM " + quote(artifact));
        views.put(artifact, view);
        quota.run();
    }

    private void stream(String artifact, String headers, long asOf) throws SQLException {
        String sql = "SELECT id,_lifecycle_id,_valid_until_epoch_ms FROM " + quote(artifact) + " WHERE _valid_until_epoch_ms>?";
        try (var select = base.prepareStatement(sql); var insert = working.prepareStatement("INSERT INTO " + quote(headers) + " VALUES(?,?,?)")) {
            select.setLong(1, asOf);
            try (var rows = select.executeQuery()) {
                int batch = 0;
                while (rows.next()) {
                    if (Thread.currentThread().isInterrupted()) { throw new SQLException("Slot membership hydration interrupted"); }
                    insert.setLong(1, rows.getLong(1)); insert.setLong(2, rows.getLong(2)); insert.setLong(3, rows.getLong(3));
                    minimumDeadline = Math.min(minimumDeadline, rows.getLong(3));
                    insert.addBatch();
                    if (++batch == 256) { insert.executeBatch(); insert.clearBatch(); batch = 0; quota.run(); }
                }
                insert.executeBatch();
            }
        }
    }

    JdbcExportSlotRegistry registry() { return new JdbcExportSlotRegistry(views); }
    long minimumDeadline() { return minimumDeadline; }
}
