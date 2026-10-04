package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.port.out.artifact.RowCursor;
import com.iocextractor.application.port.out.artifact.RowSource;
import java.sql.Connection;

/** An adapter-private row source that can reuse its consumer's transaction connection. */
interface JdbcConnectionRowSource<T> extends RowSource<T> {
    RowCursor<T> open(Connection connection);
}
