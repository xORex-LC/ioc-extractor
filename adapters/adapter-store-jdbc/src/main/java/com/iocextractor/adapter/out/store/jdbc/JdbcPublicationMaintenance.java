package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import java.sql.SQLException;
import java.util.List;

/** One bounded page per backstop tick, with durable rotation so retained families cannot starve later work. */
final class JdbcPublicationMaintenance {
    record Result(String family, JdbcPublicationVersionCleanup.Page page) { }
    private final JdbcStagedCanonicalBackend backend;
    private final List<String> families;

    JdbcPublicationMaintenance(JdbcStagedCanonicalBackend backend) {
        this.backend = backend;
        var names = new java.util.ArrayList<>(backend.store().tables().stream().map(JdbcPublicationTable::name).sorted().toList());
        names.add("$journal"); names.add("$headers"); names.add("$statistics"); families = List.copyOf(names);
    }

    Result tick(int limit) {
        JdbcPublicationCleanupCursor.requireLimit(limit);
        var transactions = new JdbcPublicationTransactions(backend.configuration().dataSource(), backend.configuration().admission());
        try {
            // Rotation is a separate short transaction. If a process dies between it and the page, the next complete
            // rotation revisits that family; per-family keyset progress advances atomically only with its deletions.
            int index = transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE, c -> {
                long next = JdbcPublicationCleanupCursor.read(c, "$rotation");
                if (next >= families.size()) { throw new SQLException("Publication maintenance catalog changed"); }
                JdbcPublicationCleanupCursor.write(c, "$rotation", (next + 1) % families.size());
                return (int) next;
            });
            String family = families.get(index);
            var cleanup = backend.cleanup();
            var page = switch (family) {
                case "$journal" -> cleanup.purgeJournal(limit);
                case "$headers" -> transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE,
                        c -> JdbcPublicationHeaderCleanup.purge(c, backend.store(), limit));
                case "$statistics" -> transactions.execute(JdbcWriterAdmission.OperationClass.MAINTENANCE,
                        c -> JdbcPublicationStatistics.optimize(c, backend.configuration().allocatorClock().millis()));
                default -> cleanup.scanVersions(family, limit);
            };
            return new Result(family, page);
        } catch (SQLException failure) { throw new IocExtractorException("Staged publication maintenance failed", failure); }
    }
}
