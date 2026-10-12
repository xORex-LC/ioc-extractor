package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static com.iocextractor.adapter.out.store.jdbc.JdbcPublicationTable.execute;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.joinedQuoted;
import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/** Immutable payload storage; the shared protocol and parent views remain publication authority. */
final class JdbcImmutableReceiptPayload {
    private JdbcImmutableReceiptPayload() { }

    static void create(Connection connection, JdbcPublicationTable table) throws SQLException {
        execute(connection, "CREATE TABLE " + quote(table.versions()) + " AS SELECT " + joinedQuoted(table.columns())
                + ",CAST(NULL AS INTEGER) AS unit_id,CAST(NULL AS INTEGER) AS fence,CAST(NULL AS INTEGER) AS stamp,"
                + "CAST(NULL AS TEXT) AS address,CAST(NULL AS TEXT) AS publication_columns FROM " + quote(table.name()) + " WHERE 0");
        execute(connection, "CREATE UNIQUE INDEX " + quote(table.versions() + "_attempt") + " ON "
                + quote(table.versions()) + "(unit_id,fence,address)");
        execute(connection, "CREATE INDEX " + quote(table.versions() + "_key") + " ON "
                + quote(table.versions()) + "(" + joinedQuoted(table.keys()) + ")");
        execute(connection, "CREATE INDEX " + quote(table.versions() + "_parent") + " ON "
                + quote(table.versions()) + "(receipt_id,stamp)");
    }

    static void createView(Connection connection, JdbcPublicationTable table) throws SQLException {
        String fields = table.columns().stream().map(column -> JdbcPublicationTimes.project(table, column, false))
                .collect(Collectors.joining(","));
        String artifact = table.name().equals("confirmation_receipt_field_position") ? "v.artifact"
                : "'" + table.name().substring(0, table.name().length() - "_receipt_rows".length()).replace("'", "''") + "'";
        execute(connection, "CREATE VIEW " + quote(table.name()) + " AS SELECT " + fields + " FROM " + quote(table.versions())
                + " v JOIN publication_unit u ON u.id=v.unit_id AND u.fence=v.fence AND u.state='COMMITTED'"
                + " WHERE " + parentVisible("v")
                + " AND EXISTS(SELECT 1 FROM confirmation_receipt_artifact a WHERE a.receipt_id=v.receipt_id AND a.artifact=" + artifact + ")");
        // Native keys remain unique among visible payloads; concurrently hidden attempts still use the shared epoch validator.
        execute(connection, "CREATE TRIGGER " + quote(table.versions() + "_immutable_key") + " BEFORE INSERT ON "
                + quote(table.versions()) + " WHEN EXISTS(SELECT 1 FROM " + quote(table.name()) + " old WHERE "
                + table.matchKeys("old", "NEW") + ") BEGIN SELECT RAISE(ABORT,'Duplicate immutable receipt payload'); END");
    }

    static String parentVisible(String alias) {
        return "EXISTS(SELECT 1 FROM confirmation_receipt r WHERE r.receipt_id=" + alias + ".receipt_id)"
                + " AND NOT EXISTS(SELECT 1 FROM publication_version_confirmation_receipt retired"
                + " JOIN publication_unit ru ON ru.id=retired.unit_id AND ru.fence=retired.fence AND ru.state='COMMITTED'"
                + " WHERE retired.receipt_id=" + alias + ".receipt_id AND retired.deleted=1 AND retired.stamp>" + alias + ".stamp)";
    }

    static boolean retainEffect(Connection working, JdbcPrivateChanges.Effect effect) throws SQLException {
        if (!effect.deleted()) {
            if (effect.previous() != null) { throw new SQLException("Immutable receipt payload cannot be updated"); }
            return true;
        }
        try (var statement = working.prepareStatement("SELECT 1 FROM confirmation_receipt WHERE receipt_id=?")) {
            statement.setObject(1, effect.value("receipt_id"));
            try (var rows = statement.executeQuery()) {
                if (rows.next()) { throw new SQLException("Immutable receipt payload requires parent retirement"); }
            }
        }
        return false;
    }

    static String preserveRetirementBarrier(JdbcTypedPublicationStore store, String alias) {
        List<String> references = store.tables().stream().filter(table -> store.receiptPayloads().contains(table, store.artifacts()))
                .map(table -> "EXISTS(SELECT 1 FROM " + quote(table.versions()) + " child WHERE child.receipt_id=" + alias
                        + ".receipt_id AND child.stamp<" + alias + ".stamp)").toList();
        return references.isEmpty() ? "1" : "NOT(" + alias + ".deleted=1 AND u.state='COMMITTED' AND(" + String.join(" OR ", references) + "))";
    }
}
