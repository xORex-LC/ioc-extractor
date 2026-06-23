package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDataSourceFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void creates_parent_directory_and_applies_sqlite_pragmas() throws Exception {
        Path db = tempDir.resolve("nested/service.db");
        SqliteDataSourceFactory factory = new SqliteDataSourceFactory(new SqlitePragmaPolicy());

        try (ManagedSqliteDataSource dataSource = factory.create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 2));
             Connection connection = dataSource.getConnection()) {

            assertThat(db.getParent()).isDirectory();
            assertThat(textPragma(connection, "journal_mode")).isEqualTo("wal");
            assertThat(textPragma(connection, "encoding")).isEqualTo("UTF-8");
            assertThat(intPragma(connection, "auto_vacuum")).isEqualTo(2);
            assertThat(intPragma(connection, "foreign_keys")).isEqualTo(1);
            assertThat(intPragma(connection, "synchronous")).isEqualTo(1);
            assertThat(intPragma(connection, "busy_timeout")).isEqualTo(5_000);
            assertThat(intPragma(connection, "cache_size")).isEqualTo(-2_000);
            assertThat(longPragma(connection, "mmap_size")).isZero();
            assertThat(intPragma(connection, "temp_store")).isZero();
            assertThat(intPragma(connection, "wal_autocheckpoint")).isEqualTo(1_000);
            assertThat(longPragma(connection, "journal_size_limit")).isEqualTo(8_388_608L);
        }
    }

    private String textPragma(Connection connection, String name) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA " + name)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private int intPragma(Connection connection, String name) throws Exception {
        return Math.toIntExact(longPragma(connection, name));
    }

    private long longPragma(Connection connection, String name) throws Exception {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA " + name)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }
}
