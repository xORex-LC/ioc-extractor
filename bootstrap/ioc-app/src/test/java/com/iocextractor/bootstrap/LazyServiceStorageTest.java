package com.iocextractor.bootstrap;

import com.iocextractor.diagnostics.sink.NoopDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyServiceStorageTest {

    @Test
    void preserves_migration_failure_when_datasource_close_also_fails() {
        var closeFailure = new IllegalStateException("datasource close failed");
        var dataSource = new FailingDataSource(closeFailure);
        var storage = new LazyServiceStorage(
                new IocProperties.Storage.Service(
                        StorageType.JDBC,
                        "jdbc:sqlite:service.db",
                        new IocProperties.Storage.Sqlite("production"),
                        new IocProperties.Storage.Pool(1, 1)),
                NoopDiagnosticSink.INSTANCE,
                Clock.systemUTC(),
                ignored -> dataSource);

        assertThatThrownBy(storage::dataSource)
                .isNotSameAs(closeFailure)
                .hasRootCauseMessage("migration connection failed")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(closeFailure));
    }

    private static final class FailingDataSource extends HikariDataSource {
        private final RuntimeException closeFailure;

        private FailingDataSource(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("migration connection failed");
        }

        @Override
        public void close() {
            throw closeFailure;
        }
    }
}
