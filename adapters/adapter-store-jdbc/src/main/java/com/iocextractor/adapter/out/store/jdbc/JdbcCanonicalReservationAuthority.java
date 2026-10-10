package com.iocextractor.adapter.out.store.jdbc;

import java.util.Objects;
import java.time.Clock;
import javax.sql.DataSource;

/** One authoritative datasource and physical admission for independently committed canonical ID ranges. */
record JdbcCanonicalReservationAuthority(DataSource dataSource, Clock clock, JdbcWriterAdmission admission) {
    JdbcCanonicalReservationAuthority {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(clock, "clock");
    }
}
