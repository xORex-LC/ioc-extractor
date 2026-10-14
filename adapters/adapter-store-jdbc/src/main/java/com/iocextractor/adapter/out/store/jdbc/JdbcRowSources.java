package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import java.sql.Connection;

/** Keeps receipt reads inside the writer transaction, including with a one-connection pool. */
final class JdbcRowSources {
    private JdbcRowSources() { }

    static <T> RowSource<T> onConnection(RowSource<T> source, Connection connection) {
        return new RowSource<>() {
            public int size() { return source.size(); }
            public RowCursor<T> open() {
                return source instanceof JdbcConnectionRowSource<T> jdbc ? jdbc.open(connection) : source.open();
            }
        };
    }
}
