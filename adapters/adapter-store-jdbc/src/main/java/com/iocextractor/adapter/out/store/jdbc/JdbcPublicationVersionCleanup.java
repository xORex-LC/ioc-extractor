package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import java.sql.SQLException;

/** Bounded physical reclamation; latest tombstones, referenced anchors and live attempts remain authoritative. */
final class JdbcPublicationVersionCleanup {
    private final JdbcTypedPublicationStore store;
    private final JdbcPublicationTransactions transactions;
    JdbcPublicationVersionCleanup(JdbcStagedCanonicalBackend backend) {
        store = backend.store(); transactions = new JdbcPublicationTransactions(backend.configuration().dataSource(), backend.configuration().admission());
    }
    int purgeVersions(String family, int limit) {
        if (limit < 1 || limit > 256) { throw new IllegalArgumentException("Version cleanup is bounded to 256 rows"); }
        var table = store.tables().stream().filter(candidate -> candidate.name().equals(family)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown publication family"));
        String versions = JdbcSql.quote(table.versions());
        String eligible = "u.fence<>v.fence OR u.state='CANCELLED' OR(u.state='COMMITTED' AND NOT "
                + JdbcTypedVersionTable.latest(table, "v") + ")";
        String sql = "DELETE FROM " + versions + " WHERE rowid IN(SELECT v.rowid FROM " + versions
                + " v JOIN publication_unit u ON u.id=v.unit_id WHERE " + eligible + " ORDER BY v.rowid LIMIT ?)";
        try {
            return transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE, c -> {
                try (var statement = c.prepareStatement(sql)) { statement.setInt(1, limit); return statement.executeUpdate(); }
            });
        } catch (SQLException failure) { throw new IocExtractorException("Cannot reclaim obsolete canonical versions", failure); }
    }
}
