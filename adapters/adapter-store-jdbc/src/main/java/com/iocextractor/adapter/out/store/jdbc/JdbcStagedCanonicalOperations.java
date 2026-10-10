package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/** Operational candidate scope. No default Spring switch; activation still requires the resource and ADR gates. */
final class JdbcStagedCanonicalOperations implements AutoCloseable {
    enum State { RECOVERY_REQUIRED, READY, CLOSED }
    record Health(State state, boolean storageHealthy, boolean pendingRecovery, boolean ownerOccupied) {
        boolean ready() { return state == State.READY && storageHealthy && !pendingRecovery; }
    }
    private final JdbcStagedCanonicalBackend backend;
    private final JdbcStagedDatabaseLease lease;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private volatile State state = State.RECOVERY_REQUIRED;
    private volatile boolean maintenanceHealthy = true;

    private JdbcStagedCanonicalOperations(JdbcStagedCanonicalBackend backend, JdbcStagedDatabaseLease lease) {
        this.backend = backend; this.lease = lease;
    }

    static JdbcStagedCanonicalOperations migrateDrained(JdbcStagedCanonicalConfiguration configuration,
                                                       DataSource service) {
        return openOwned(configuration, service, true);
    }

    static JdbcStagedCanonicalOperations reopen(JdbcStagedCanonicalConfiguration configuration) {
        return openOwned(configuration, null, false);
    }

    private static JdbcStagedCanonicalOperations openOwned(JdbcStagedCanonicalConfiguration configuration,
                                                         DataSource service, boolean migrate) {
        JdbcStagedDatabaseLease lease = null;
        try {
            try (var connection = configuration.dataSource().getConnection()) { lease = JdbcStagedDatabaseLease.acquire(connection); }
            JdbcStagedCanonicalBackend backend;
            if (migrate) {
                try (var connection = service.getConnection()) {
                    JdbcStagedCanonicalMigration.requireVersion(connection, ServiceSchemaMigrations.sqlite().getLast().version());
                    JdbcStagedDrainCheck.requireDrained(connection);
                }
                var transactions = new JdbcPublicationTransactions(configuration.dataSource(), configuration.admission());
                var store = transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL,
                        c -> JdbcStagedCanonicalMigration.migrate(c, configuration));
                backend = new JdbcStagedCanonicalBackend(configuration, store, JdbcPublicationObserver.NOOP);
            } else {
                try (var connection = configuration.dataSource().getConnection()) {
                    JdbcStagedCanonicalMigration.requireVersion(connection, JdbcStagedCanonicalMigration.STAGED_VERSION);
                }
                backend = JdbcStagedCanonicalBackend.reopen(configuration, JdbcPublicationObserver.NOOP);
            }
            return new JdbcStagedCanonicalOperations(backend, lease);
        } catch (SQLException | IOException | RuntimeException | Error failure) {
            if (lease != null) {
                try { lease.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            }
            if (failure instanceof Error error) { throw error; }
            throw new IocExtractorException("Cannot open operational staged canonical candidate", failure);
        }
    }

    /** Bounded startup backstop, before accepting work. Process lease proves previous operational owner has ended. */
    boolean recoverPage(int limit) {
        JdbcPublicationCleanupCursor.requireLimit(limit);
        if (state == State.READY) { requireOpen(); return true; }
        lifetime.writeLock().lock();
        try {
            requireOpen();
            if (state == State.READY) { return true; }
            var transactions = new JdbcPublicationTransactions(backend.configuration().dataSource(), backend.configuration().admission());
            boolean complete = transactions.execute(JdbcWriterAdmission.OperationClass.CONTROL, c -> {
                var owners = new JdbcPublicationOwner();
                owners.releaseCommitted(c);
                try (var statement = c.createStatement(); var rows = statement.executeQuery(
                        "SELECT unit,fence FROM publication_logical_owner WHERE singleton_id=1 AND unit IS NOT NULL")) {
                    if (rows.next()) {
                        owners.fail(c, new JdbcPublicationOwner.Owner(rows.getString(1), rows.getLong(2)),
                                backend.configuration().allocatorClock().millis(), "PUBLICATION.PROCESS_RESTART");
                    }
                }
                try (var statement = c.prepareStatement("UPDATE publication_unit SET state='CANCELLED' WHERE id IN("
                        + "SELECT id FROM publication_unit WHERE state IN('PENDING','SEALED') ORDER BY id LIMIT ?)")) {
                    statement.setInt(1, limit); statement.executeUpdate();
                }
                return !pending(c);
            });
            if (complete) { state = State.READY; }
            return complete;
        } catch (SQLException failure) { throw new IocExtractorException("Staged startup recovery failed", failure); }
        finally { lifetime.writeLock().unlock(); }
    }

    /** Borrow ports only within this call. The owning composition must not retain a backend outside its lifetime. */
    <T> T use(Function<JdbcStagedCanonicalBackend, T> work) {
        lifetime.readLock().lock();
        try {
            requireOpen();
            if (state != State.READY) { throw new IllegalStateException("Staged startup recovery is incomplete"); }
            return work.apply(backend);
        } finally { lifetime.readLock().unlock(); }
    }

    Health health() {
        var storage = new JdbcStorageHealthProbe(backend.configuration().dataSource(), "staged-dataframe").probe();
        try {
            lease.requireHeld();
            boolean clockSafe = backend.configuration().clock().inspect().status()
                    != com.iocextractor.application.artifact.lifecycle.LifecycleClockStatus.UNSAFE;
            try (var connection = backend.configuration().dataSource().getConnection()) {
                connection.setAutoCommit(false);
                JdbcStagedCanonicalMigration.requireVersion(connection, JdbcStagedCanonicalMigration.STAGED_VERSION);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT pins FROM publication_configuration WHERE singleton_id=1")) {
                    if (!rows.next() || !backend.configuration().fingerprint().equals(rows.getString(1))) {
                        return new Health(state, false, true, true);
                    }
                }
                boolean pending = pending(connection);
                boolean owned;
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT unit FROM publication_logical_owner WHERE singleton_id=1")) {
                    owned = !rows.next() || rows.getString(1) != null;
                }
                connection.rollback();
                // During normal execution hidden work is healthy; only startup recovery controls readiness.
                return new Health(state, storage.healthy() && maintenanceHealthy && clockSafe, state != State.READY && pending, owned);
            }
        } catch (SQLException | RuntimeException failure) { return new Health(state, false, true, true); }
    }

    JdbcPublicationMaintenance.Result maintenancePage(int limit) {
        boolean successful = false;
        try {
            var result = use(b -> new JdbcPublicationMaintenance(b).tick(limit));
            successful = true;
            return result;
        } finally { maintenanceHealthy = successful; }
    }

    private static boolean pending(java.sql.Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 1 FROM publication_unit WHERE state IN('PENDING','SEALED') LIMIT 1")) { return rows.next(); }
    }

    private void requireOpen() {
        lease.requireHeld();
        if (state == State.CLOSED) { throw new IllegalStateException("Staged operational scope is closed"); }
    }

    /** Never releases the process lease beneath live work. Caller cancels/drains its existing owned workers first. */
    @Override public void close() throws IOException {
        if (!lifetime.writeLock().tryLock()) { throw new IOException("Staged work must drain before releasing database ownership"); }
        try { if (state != State.CLOSED) { lease.close(); state = State.CLOSED; } }
        finally { lifetime.writeLock().unlock(); }
    }
}
