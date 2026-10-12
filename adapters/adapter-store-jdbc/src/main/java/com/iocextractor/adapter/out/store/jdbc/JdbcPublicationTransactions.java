package com.iocextractor.adapter.out.store.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Short admitted physical transactions; no native preparation work executes inside this admission. */
final class JdbcPublicationTransactions {
    private final DataSource dataSource;
    private final JdbcWriterAdmission admission;
    private final JdbcPublicationObserver observer;

    JdbcPublicationTransactions(DataSource dataSource, JdbcWriterAdmission admission) {
        this(dataSource, admission, JdbcPublicationObserver.NOOP);
    }

    JdbcPublicationTransactions(DataSource dataSource, JdbcWriterAdmission admission, JdbcPublicationObserver observer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    <T> T execute(JdbcWriterAdmission.OperationClass operation, Work<T> work) throws SQLException {
        try {
            return admission.execute(operation, () -> {
                try { return transaction(work); }
                catch (SQLException failure) { throw new SqlFailure(failure); }
            });
        } catch (SqlFailure failure) {
            throw failure.sqlCause();
        }
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (var connection = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.CONNECTION_CHECKOUT,
                dataSource::getConnection)) {
            connection.setAutoCommit(false);
            try {
                T result = JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.TRANSACTION,
                        () -> work.apply(connection));
                JdbcPublicationObserver.measure(observer, JdbcPublicationObserver.Timing.COMMIT,
                        () -> { connection.commit(); return null; });
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    @FunctionalInterface interface Work<T> { T apply(Connection connection) throws SQLException; }
    private static final class SqlFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final SQLException sqlCause;
        SqlFailure(SQLException cause) { super(cause); sqlCause = cause; }
        SQLException sqlCause() { return sqlCause; }
    }
}
