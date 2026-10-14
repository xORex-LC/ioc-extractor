package com.iocextractor.adapter.out.store.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class JdbcStatementScopeTest {
    @Test
    void releases_bindings_and_batches_without_committing_or_closing_the_connection() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var ddl = connection.createStatement()) {
                ddl.execute("CREATE TABLE sample(value TEXT)");
            }
            connection.setAutoCommit(false);
            PreparedStatement cached;
            try (var scope = new JdbcStatementScope(connection)) {
                try (var lease = scope.borrow("INSERT INTO sample VALUES (?)")) {
                    cached = lease.statement();
                    cached.setString(1, "discarded-batch");
                    cached.addBatch();
                }
                try (var lease = scope.borrow("INSERT INTO sample VALUES (?)")) {
                    assertThat(lease.statement()).isSameAs(cached);
                    lease.statement().setString(1, "kept");
                    lease.statement().addBatch();
                    assertThat(lease.statement().executeBatch()).hasSize(1);
                }
            }
            assertThat(cached.isClosed()).isTrue();
            assertThat(connection.isClosed()).isFalse();
            assertThat(connection.getAutoCommit()).isFalse();
            connection.rollback();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM sample")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    @Test
    void bounds_dynamic_statements_and_rejects_reentrant_leases() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var scope = new JdbcStatementScope(connection)) {
            PreparedStatement oldest;
            try (var lease = scope.borrow("SELECT 0")) {
                oldest = lease.statement();
                assertThatThrownBy(() -> scope.borrow("SELECT 0")).isInstanceOf(IllegalStateException.class);
            }
            for (int index = 1; index <= 32; index++) {
                try (var lease = scope.borrow("SELECT " + index)) {
                    try (var rows = lease.statement().executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(index);
                    }
                }
            }
            assertThat(oldest.isClosed()).isTrue();
        }
    }

    @Test
    void never_evicts_live_leases_when_the_statement_limit_is_reached() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var scope = new JdbcStatementScope(connection)) {
            var leases = new ArrayList<JdbcStatementScope.Lease>();
            try {
                for (int index = 0; index < 32; index++) {
                    leases.add(scope.borrow("SELECT " + index));
                }
                assertThatThrownBy(() -> scope.borrow("SELECT 32"))
                        .isInstanceOf(IllegalStateException.class).hasMessage("All bounded statements are leased");
                PreparedStatement released = leases.get(16).statement();
                leases.get(16).close();
                try (var replacement = scope.borrow("SELECT 32")) {
                    assertThat(released.isClosed()).isTrue();
                    try (var rows = leases.getFirst().statement().executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isZero();
                    }
                    assertThat(replacement.statement().isClosed()).isFalse();
                }
            } finally {
                for (var lease : leases) {
                    lease.close();
                }
            }
        }
    }

    @Test
    void released_leases_cannot_access_a_statement_reborrowed_by_the_next_caller() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var scope = new JdbcStatementScope(connection)) {
            var previous = scope.borrow("SELECT ?");
            previous.statement().setInt(1, 10);
            previous.close();
            assertThatThrownBy(previous::statement).isInstanceOf(IllegalStateException.class)
                    .hasMessage("JDBC statement lease is closed");
            try (var current = scope.borrow("SELECT ?")) {
                current.statement().setInt(1, 20);
                previous.close();
                try (var rows = current.statement().executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(20);
                }
            }
        }
    }

    @Test
    void closes_all_statements_and_preserves_close_failures() throws Exception {
        try (var actual = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            List<PreparedStatement> prepared = new ArrayList<>();
            Connection connection = failingConnection(actual, prepared, "close");
            var scope = new JdbcStatementScope(connection);
            try (var first = scope.borrow("SELECT 1"); var second = scope.borrow("SELECT 2")) {
                assertThat(first.statement()).isNotSameAs(second.statement());
            }
            assertThatThrownBy(scope::close).isInstanceOf(SQLException.class)
                    .hasMessage("injected close failure").satisfies(failure ->
                            assertThat(failure.getSuppressed()).hasSize(1));
            for (var statement : prepared) {
                assertThat(statement.isClosed()).isTrue();
            }
            scope.close();
            assertThatThrownBy(() -> scope.borrow("SELECT 3")).isInstanceOf(IllegalStateException.class);
            assertThat(actual.isClosed()).isFalse();
        }
    }

    @Test
    void discards_a_statement_when_clearing_its_lease_fails() throws Exception {
        try (var actual = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            List<PreparedStatement> prepared = new ArrayList<>();
            try (var scope = new JdbcStatementScope(failingConnection(actual, prepared, "clearBatch"))) {
                var first = scope.borrow("SELECT 1");
                assertThatThrownBy(first::close).isInstanceOf(SQLException.class);
                assertThat(prepared.getFirst().isClosed()).isTrue();
                var retry = scope.borrow("SELECT 1");
                assertThat(prepared).hasSize(2);
                assertThatThrownBy(retry::close).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void rejects_use_and_close_from_another_thread() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var scope = new JdbcStatementScope(connection)) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    assertThatThrownBy(() -> scope.borrow("SELECT 1")).isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(scope::close).isInstanceOf(IllegalStateException.class);
                }).get(3, TimeUnit.SECONDS);
            } finally {
                worker.shutdownNow();
                assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            }
            try (var lease = scope.borrow("SELECT 1")) {
                assertThat(lease.statement().isClosed()).isFalse();
            }
        }
    }

    private Connection failingConnection(Connection actual, List<PreparedStatement> prepared, String failureMethod) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(actual, arguments);
                        if (!method.getName().equals("prepareStatement")) {
                            return result;
                        }
                        PreparedStatement statement = (PreparedStatement) result;
                        prepared.add(statement);
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (statementProxy, call, parameters) -> {
                                    try {
                                        Object value = call.invoke(statement, parameters);
                                        if (call.getName().equals(failureMethod)) {
                                            throw new SQLException("injected " + failureMethod + " failure");
                                        }
                                        return value;
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
