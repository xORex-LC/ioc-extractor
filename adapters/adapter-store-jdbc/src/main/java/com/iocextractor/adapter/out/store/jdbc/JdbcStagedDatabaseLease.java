package com.iocextractor.adapter.out.store.jdbc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;

/** Exclusive local process lifetime for operational candidate entry points, never an age-based takeover. */
final class JdbcStagedDatabaseLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private JdbcStagedDatabaseLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    static JdbcStagedDatabaseLease acquire(Connection connection) throws SQLException, IOException {
        Path database;
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA database_list")) {
            database = null;
            while (rows.next()) {
                if (rows.getString("name").equals("main") && !rows.getString("file").isBlank()) {
                    database = Path.of(rows.getString("file")).toRealPath();
                }
            }
        }
        if (database == null) { throw new SQLException("Operational staged publication requires file-backed SQLite"); }
        Path lease = database.resolveSibling(database.getFileName() + ".staged-lease");
        var channel = FileChannel.open(lease, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) { throw new IOException("Staged database is owned by another process"); }
            return new JdbcStagedDatabaseLease(channel, lock);
        } catch (IOException | RuntimeException | Error failure) {
            try { channel.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            if (failure instanceof OverlappingFileLockException) { throw new IOException("Staged database already owned in this process", failure); }
            throw failure;
        }
    }

    void requireHeld() {
        if (!lock.isValid() || !channel.isOpen()) { throw new IllegalStateException("Staged database lease is closed"); }
    }

    @Override public void close() throws IOException { channel.close(); }
}
