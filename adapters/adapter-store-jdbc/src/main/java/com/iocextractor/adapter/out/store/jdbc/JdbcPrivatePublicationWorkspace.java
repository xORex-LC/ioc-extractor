package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;

/** Private native state shares document admission, quotas and leases; canonical identity never lives here. */
final class JdbcPrivatePublicationWorkspace implements AutoCloseable {
    private final JdbcDocumentPreparationWorkspaceFactory.CanonicalLease lease;
    private final HikariDataSource pool;
    private final DataSource dataSource;

    private JdbcPrivatePublicationWorkspace(JdbcDocumentPreparationWorkspaceFactory.CanonicalLease lease,
                                           HikariDataSource pool) {
        this.lease = lease;
        this.pool = pool;
        pool.setMaximumPoolSize(1);
        this.dataSource = new BoundedDataSource(pool, lease.limits());
    }

    static JdbcPrivatePublicationWorkspace create(JdbcDocumentPreparationWorkspaceFactory factory,
                                                  String attempt, String pins) {
        var lease = factory.openCanonicalState(attempt, pins);
        HikariDataSource pool = null;
        try {
            var path = lease.directory().resolve("native.db");
            if (Files.exists(path)) { throw new IOException("A native attempt workspace must be fresh"); }
            pool = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                    new SqliteDataSourceSettings("canonical-private", "jdbc:sqlite:" + path, "low-memory", 1, 1));
            return new JdbcPrivatePublicationWorkspace(lease, pool);
        } catch (IOException | RuntimeException | Error failure) {
            if (pool != null) {
                try { pool.close(); } catch (RuntimeException closing) { failure.addSuppressed(closing); }
            }
            try { lease.close(); } catch (RuntimeException closing) { failure.addSuppressed(closing); }
            if (failure instanceof Error fatal) { throw fatal; }
            throw new IocExtractorException("Cannot create private canonical state", failure);
        }
    }

    DataSource dataSource() { return dataSource; }
    DocumentPreparationLimits limits() { return lease.limits(); }
    void checkQuota() { lease.checkQuota(); }

    void seal(String pins) {
        checkQuota();
        try { DocumentWorkspaceFiles.write(lease.directory().resolve("seal"), pins); }
        catch (IOException failure) { throw new IocExtractorException("Cannot seal native preparation", failure); }
    }

    @Override public void close() {
        lease.requireOwner();
        try (var ignored = lease) { pool.close(); lease.discard(); }
    }

    private static final class BoundedDataSource extends DelegatingDataSource {
        private final DocumentPreparationLimits limits;
        private final Thread owner = Thread.currentThread();

        BoundedDataSource(DataSource dataSource, DocumentPreparationLimits limits) {
            super(dataSource); this.limits = limits;
        }

        @Override public Connection getConnection() throws SQLException {
            if (Thread.currentThread() != owner) { throw new SQLException("Private state belongs to another thread"); }
            Connection connection = super.getConnection();
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA cache_size=-" + limits.cacheKiB());
                statement.execute("PRAGMA temp_store=FILE");
                statement.execute("PRAGMA temp.cache_size=-" + limits.cacheKiB());
                statement.execute("PRAGMA mmap_size=0");
                // Reserve room for WAL and TEMP; aggregate filesystem checks remain authoritative.
                statement.execute("PRAGMA max_page_count=" + Math.max(16, limits.workspaceBytes() / (4 * 4096)));
                statement.execute("PRAGMA temp.max_page_count=" + Math.max(16, limits.workspaceBytes() / (4 * 4096)));
                return connection;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }

        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Private state does not accept alternate connection credentials");
        }
    }
}
