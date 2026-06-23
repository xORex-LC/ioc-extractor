package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Hikari-backed datasource that applies SQLite PRAGMAs on every connection and
 * during initial database creation.
 */
public final class ManagedSqliteDataSource implements DataSource, AutoCloseable {

    private final HikariDataSource delegate;
    private final SqlitePragmaSettings pragmas;

    ManagedSqliteDataSource(HikariDataSource delegate, SqlitePragmaSettings pragmas) {
        this.delegate = delegate;
        this.pragmas = pragmas;
    }

    public void initializePersistentPragmas() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA encoding='" + pragmas.encoding() + "'");
            statement.execute("PRAGMA auto_vacuum=" + pragmas.autoVacuum());
            statement.execute("PRAGMA journal_mode=" + pragmas.journalMode());
        } catch (SQLException e) {
            throw new IocExtractorException("Failed to initialize SQLite persistent PRAGMAs", e);
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyConnectionPragmas(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyConnectionPragmas(delegate.getConnection(username, password));
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private Connection applyConnectionPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=" + (pragmas.foreignKeys() ? "ON" : "OFF"));
            statement.execute("PRAGMA synchronous=" + pragmas.synchronous().name());
            statement.execute("PRAGMA busy_timeout=" + pragmas.busyTimeout().toMillis());
            statement.execute("PRAGMA cache_size=" + pragmas.cacheSize());
            statement.execute("PRAGMA mmap_size=" + pragmas.mmapSize());
            statement.execute("PRAGMA temp_store=" + pragmas.tempStore().name());
            statement.execute("PRAGMA wal_autocheckpoint=" + pragmas.walAutocheckpoint());
            statement.execute("PRAGMA journal_size_limit=" + pragmas.journalSizeLimit());
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }
}
