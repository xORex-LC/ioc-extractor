package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Hikari-backed SQLite datasources with adapter-owned correctness
 * PRAGMAs applied.
 */
public final class SqliteDataSourceFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDataSourceFactory.class);
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private final SqlitePragmaPolicy pragmaPolicy;

    public SqliteDataSourceFactory(SqlitePragmaPolicy pragmaPolicy) {
        this.pragmaPolicy = pragmaPolicy;
    }

    public ManagedSqliteDataSource create(SqliteDataSourceSettings settings) {
        ensureParentDirectory(settings.jdbcUrl());
        SqlitePragmaSettings pragmas = pragmaPolicy.effective(settings.tuning());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setPoolName("ioc-" + settings.role() + "-sqlite");
        config.setMaximumPoolSize(settings.maxPoolSize());
        config.setMinimumIdle(0);
        config.setAutoCommit(true);

        ManagedSqliteDataSource dataSource = new ManagedSqliteDataSource(new HikariDataSource(config), pragmas);
        dataSource.initializePersistentPragmas();
        LOGGER.info("sqlite datasource initialized role={} url={} tuning={} maxPoolSize={} busyTimeoutMs={}",
                settings.role(), settings.jdbcUrl(), settings.tuning(), settings.maxPoolSize(),
                pragmas.busyTimeout().toMillis());
        return dataSource;
    }

    private void ensureParentDirectory(String jdbcUrl) {
        String location = jdbcUrl.startsWith(SQLITE_PREFIX) ? jdbcUrl.substring(SQLITE_PREFIX.length()) : jdbcUrl;
        if (location.isBlank() || location.equals(":memory:") || location.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(location).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IocExtractorException("Failed to create SQLite database directory: " + parent, e);
        }
    }
}
