package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;
import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;

/** Physical immutable versions and the single committed visibility predicate for one typed family. */
final class JdbcTypedVersionTable {
    private JdbcTypedVersionTable() { }

    static void create(Connection connection, JdbcPublicationTable table, boolean artifact) throws SQLException {
        execute(connection, "CREATE TABLE " + quote(table.versions()) + " AS SELECT "
                + joinedQuoted(table.columns()) + ",CAST(NULL AS INTEGER) AS unit_id,CAST(NULL AS INTEGER) AS fence,"
                + "CAST(NULL AS INTEGER) AS stamp,CAST(NULL AS TEXT) AS address,CAST(NULL AS INTEGER) AS deleted,"
                + "CAST(NULL AS TEXT) AS publication_columns,CAST(NULL AS INTEGER) AS absolute_deadline,"
                + "CAST(NULL AS INTEGER) AS deadline_anchor FROM " + quote(table.name()) + " WHERE 0");
        index(connection, table, "attempt", "unit_id,fence,address", true);
        index(connection, table, "address", "address,stamp DESC", false);
        index(connection, table, "key", joinedQuoted(table.keys()) + ",stamp DESC", false);
        if (artifact) {
            index(connection, table, "absolute_deadline", "absolute_deadline WHERE absolute_deadline IS NOT NULL", false);
            index(connection, table, "deadline_anchor", "deadline_anchor,address,stamp WHERE deadline_anchor IS NOT NULL", false);
        }
        copyLookupIndexes(connection, table);
    }

    private static void index(Connection connection, JdbcPublicationTable table, String suffix, String fields,
                              boolean unique) throws SQLException {
        int where = fields.indexOf(" WHERE ");
        String columns = where < 0 ? fields : fields.substring(0, where);
        String condition = where < 0 ? "" : fields.substring(where);
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + quote(table.versions() + "_" + suffix) + " ON " + quote(table.versions())
                + "(" + columns + ")" + condition);
    }

    private static void copyLookupIndexes(Connection connection, JdbcPublicationTable table) throws SQLException {
        // The canonical key index already starts with the complete native PK.
        // Only eliminate exact duplicate column sequences, never partial/prefix guesses.
        var covered = new java.util.HashSet<java.util.List<String>>();
        covered.add(table.keys());
        try (var indexes = connection.prepareStatement("SELECT name FROM pragma_index_list(?)")) {
            indexes.setString(1, table.name());
            try (var rows = indexes.executeQuery()) {
                int number = 0;
                while (rows.next()) {
                    var columns = new java.util.ArrayList<String>();
                    try (var info = connection.prepareStatement("SELECT name FROM pragma_index_info(?) ORDER BY seqno")) {
                        info.setString(1, rows.getString(1));
                        try (var fields = info.executeQuery()) {
                            while (fields.next()) {
                                String column = fields.getString(1);
                                if (column == null) { throw new SQLException("Expression index requires explicit publication mapping"); }
                                columns.add(column);
                            }
                        }
                    }
                    if (!columns.isEmpty() && covered.add(java.util.List.copyOf(columns))) {
                        index(connection, table, "lookup_" + number++, joinedQuoted(columns), false);
                    }
                }
            }
        }
    }

    static void createView(Connection connection, JdbcPublicationTable table, boolean artifact) throws SQLException {
        String fields = table.columns().stream().map(column -> JdbcPublicationTimes.project(table, column, artifact))
                .collect(java.util.stream.Collectors.joining(","));
        execute(connection, "CREATE VIEW " + quote(table.name()) + " AS SELECT " + fields
                + " FROM " + quote(table.versions()) + " v JOIN publication_unit u"
                + " ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'"
                + " LEFT JOIN publication_unit anchor ON anchor.id=v.deadline_anchor AND anchor.state='COMMITTED'"
                + " WHERE " + latest(table, "v") + " AND v.deleted=0");
    }

    static String latest(JdbcPublicationTable table, String alias) {
        return "NOT EXISTS(SELECT 1 FROM " + quote(table.versions()) + " newer WHERE newer.address=" + alias
                + ".address AND newer.stamp>" + alias + ".stamp AND EXISTS(SELECT 1 FROM publication_unit nu"
                + " WHERE nu.id=newer.unit_id AND nu.fence=newer.fence AND nu.state='COMMITTED'))";
    }

    static String nearestSql(JdbcPublicationTable table) {
        return "SELECT MIN(deadline) FROM (SELECT * FROM (SELECT v.absolute_deadline AS deadline FROM "
                + quote(table.versions()) + " v WHERE v.absolute_deadline>? AND v.deleted=0"
                + " AND EXISTS(SELECT 1 FROM publication_unit u WHERE u.id=v.unit_id AND u.fence=v.fence"
                + " AND u.state='COMMITTED') AND " + latest(table, "v")
                + " ORDER BY v.absolute_deadline LIMIT 1) UNION ALL SELECT * FROM ("
                + "SELECT anchor.valid_until AS deadline FROM publication_unit anchor WHERE anchor.state='COMMITTED'"
                + " AND anchor.valid_until>? AND EXISTS(SELECT 1 FROM " + quote(table.versions())
                + " v WHERE v.deadline_anchor=anchor.id AND v.deleted=0"
                + " AND EXISTS(SELECT 1 FROM publication_unit u WHERE u.id=v.unit_id AND u.fence=v.fence"
                + " AND u.state='COMMITTED') AND " + latest(table, "v")
                + ") ORDER BY anchor.valid_until LIMIT 1))";
    }

    static String dueSql(JdbcPublicationTable table) {
        String visible = "v.deleted=0 AND EXISTS(SELECT 1 FROM publication_unit u WHERE u.id=v.unit_id"
                + " AND u.fence=v.fence AND u.state='COMMITTED') AND " + latest(table, "v");
        return "SELECT id,_lifecycle_id FROM(SELECT v.id,v._lifecycle_id,v.absolute_deadline AS deadline FROM "
                + quote(table.versions()) + " v WHERE v.absolute_deadline<=? AND " + visible
                + " UNION ALL SELECT v.id,v._lifecycle_id,anchor.valid_until AS deadline FROM publication_unit anchor"
                + " CROSS JOIN " + quote(table.versions()) + " v ON v.deadline_anchor=anchor.id"
                + " WHERE anchor.state='COMMITTED' AND anchor.valid_until<=? AND " + visible
                + ") ORDER BY deadline,_lifecycle_id LIMIT ?";
    }
}
