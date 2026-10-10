package com.iocextractor.adapter.out.store.jdbc;

import java.sql.SQLException;

/** A prepared snapshot lost an epoch/deadline dependency; rebuild with identical observation and policy pins. */
final class JdbcPublicationConflict extends SQLException {
    private static final long serialVersionUID = 1L;
    JdbcPublicationConflict(String message) { super(message); }
}
