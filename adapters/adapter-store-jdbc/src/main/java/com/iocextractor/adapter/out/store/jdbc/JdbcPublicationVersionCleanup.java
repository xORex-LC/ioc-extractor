package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import java.sql.SQLException;
import java.sql.Connection;

/** Bounded physical reclamation; tombstones dominate retained versions, anchors and live attempts remain authoritative. */
final class JdbcPublicationVersionCleanup {
    record Page(int scanned, int removed, boolean wrapped) { }
    private final JdbcTypedPublicationStore store;
    private final JdbcPublicationTransactions transactions;
    JdbcPublicationVersionCleanup(JdbcStagedCanonicalBackend backend) {
        store = backend.store(); transactions = new JdbcPublicationTransactions(backend.configuration().dataSource(), backend.configuration().admission());
    }
    int purgeVersions(String family, int limit) {
        return scanVersions(family, limit).removed();
    }

    Page scanVersions(String family, int limit) {
        JdbcPublicationCleanupCursor.requireLimit(limit);
        var table = store.tables().stream().filter(candidate -> candidate.name().equals(family)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown publication family"));
        try {
            return transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE,
                    c -> scan(c, family, JdbcSql.quote(table.versions()), eligible(store, table), limit));
        } catch (SQLException failure) { throw new IocExtractorException("Cannot reclaim obsolete canonical versions", failure); }
    }

    /** Shared by reclamation and the read-only qualification census; no alternative visibility model. */
    static String eligible(JdbcTypedPublicationStore store, JdbcPublicationTable table) {
        String versions = JdbcSql.quote(table.versions());
        String eligible = "u.fence<>v.fence OR u.state='CANCELLED' OR(u.state='COMMITTED' AND NOT "
                + JdbcTypedVersionTable.latest(table, "v") + ") OR(u.state='COMMITTED' AND v.deleted=1"
                + " AND NOT EXISTS(SELECT 1 FROM " + versions + " other WHERE other.address=v.address AND other.rowid<>v.rowid))";
        if (store.receiptPayloads().contains(table, store.artifacts())) {
            eligible = "u.fence<>v.fence OR u.state='CANCELLED' OR(u.state='COMMITTED' AND NOT("
                    + JdbcImmutableReceiptPayload.parentVisible("v") + "))";
        } else if (table.name().equals("confirmation_receipt")) {
            eligible = "(" + eligible + ") AND " + JdbcImmutableReceiptPayload.preserveRetirementBarrier(store, "v");
        }
        return eligible;
    }

    private static Page scan(Connection connection, String family, String versions, String eligible, int limit) throws SQLException {
        long started = System.nanoTime();
        long after = JdbcPublicationCleanupCursor.read(connection, family);
        var addresses = new java.util.ArrayList<Long>(limit);
        // Bound scanned addresses, not just deletions: a retained prefix cannot cause an unbounded writer scan.
        try (var statement = connection.prepareStatement("SELECT rowid FROM " + versions + " WHERE rowid>? ORDER BY rowid LIMIT ?")) {
            statement.setLong(1, after); statement.setInt(2, limit);
            try (var rows = statement.executeQuery()) { while (rows.next()) { addresses.add(rows.getLong(1)); } }
        }
        int removed = 0;
        if (!addresses.isEmpty()) {
            // The interval contains at most the selected page: no other writer can insert within this transaction.
            try (var statement = connection.prepareStatement("DELETE FROM " + versions + " WHERE rowid>? AND rowid<=?"
                    + " AND EXISTS(SELECT 1 FROM " + versions + " v JOIN publication_unit u ON u.id=v.unit_id"
                    + " WHERE v.rowid=" + versions + ".rowid AND (" + eligible + "))")) {
                statement.setLong(1, after); statement.setLong(2, addresses.getLast()); removed = statement.executeUpdate();
            }
        }
        boolean wrapped = addresses.size() < limit;
        JdbcPublicationCleanupCursor.write(connection, family, wrapped ? 0 : addresses.getLast());
        var page = new Page(addresses.size(), removed, wrapped);
        JdbcPublicationCleanupCursor.record(connection, family, page, System.nanoTime() - started);
        return page;
    }

    Page purgeJournal(int limit) {
        JdbcPublicationCleanupCursor.requireLimit(limit);
        try {
            return transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE,
                    c -> {
                        long started = System.nanoTime();
                        var page = JdbcPublicationJournalCleanup.purge(c, limit);
                        JdbcPublicationCleanupCursor.record(c, "$journal", page, System.nanoTime() - started);
                        return page;
                    });
        } catch (SQLException failure) { throw new IocExtractorException("Cannot reclaim publication transfer journals", failure); }
    }
}
