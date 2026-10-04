package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded statement ownership within one caller-owned connection/transaction.
 * Thread-confined; leases clear bindings and batches but only the scope closes
 * cached statements. It never closes or commits the connection.
 */
final class JdbcStatementScope implements AutoCloseable {
    private static final int MAX_STATEMENTS = 32;
    private final Connection connection;
    private final Thread owner = Thread.currentThread();
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    JdbcStatementScope(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    Lease borrow(String sql) throws SQLException {
        requireOpen();
        Entry entry = entries.get(sql);
        if (entry == null) {
            evictIfFull();
            entry = new Entry(connection.prepareStatement(sql));
            entries.put(sql, entry);
        }
        if (entry.borrowed) {
            throw new IllegalStateException("Statement already leased in this scope");
        }
        entry.borrowed = true;
        return new Lease(entry);
    }

    private void evictIfFull() throws SQLException {
        if (entries.size() < MAX_STATEMENTS) {
            return;
        }
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (!entry.borrowed) {
                entry.statement.close();
                iterator.remove();
                return;
            }
        }
        throw new IllegalStateException("All bounded statements are leased");
    }

    void requireOpen() {
        requireOwner();
        if (closed) {
            throw new IllegalStateException("JDBC statement scope is closed");
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("JDBC statement scope belongs to another thread");
        }
    }

    @Override
    public void close() throws SQLException {
        requireOwner();
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        for (Entry entry : entries.values()) {
            try {
                entry.statement.close();
            } catch (SQLException closingFailure) {
                if (failure == null) {
                    failure = closingFailure;
                } else {
                    failure.addSuppressed(closingFailure);
                }
            }
        }
        entries.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private static final class Entry {
        private final PreparedStatement statement;
        private boolean borrowed;

        private Entry(PreparedStatement statement) {
            this.statement = statement;
        }
    }

    final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean released;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        PreparedStatement statement() {
            requireOpen();
            if (released) {
                throw new IllegalStateException("JDBC statement lease is closed");
            }
            return entry.statement;
        }

        @Override
        public void close() throws SQLException {
            requireOwner();
            if (!released) {
                released = true;
                try {
                    entry.statement.clearBatch();
                    entry.statement.clearParameters();
                } catch (SQLException clearingFailure) {
                    entries.values().remove(entry);
                    try {
                        entry.statement.close();
                    } catch (SQLException closingFailure) {
                        clearingFailure.addSuppressed(closingFailure);
                    }
                    throw clearingFailure;
                } finally {
                    entry.borrowed = false;
                }
            }
        }
    }
}
