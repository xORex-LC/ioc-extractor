package com.iocextractor.adapter.out.store.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;

/** One compare-and-set policy shared by native, privately prepared and direct projection updates. */
final class JdbcProjectionMutation {
    static final String DIRECT_TABLE = "temp.direct_projection_state";
    private final JdbcClient jdbc;
    private final String table;

    JdbcProjectionMutation(JdbcClient jdbc) { this(jdbc, "artifact_projection_state"); }

    JdbcProjectionMutation(JdbcClient jdbc, String table) {
        if (!table.equals("artifact_projection_state") && !table.equals(DIRECT_TABLE)) {
            throw new IllegalArgumentException("Unknown projection mutation table");
        }
        this.jdbc = jdbc;
        this.table = table;
    }

    boolean acknowledge(String artifact, long installed, long projectedAt) {
        return jdbc.sql("UPDATE " + table + """
                 SET projected_generation = :installed,
                     projected_at_ms = :projectedAt,
                     last_error_code = NULL
                 WHERE artifact = :artifact
                   AND required_generation >= :installed
                   AND projected_generation <= :installed
                """)
                .param("installed", installed).param("projectedAt", projectedAt)
                .param("artifact", artifact).update() == 1;
    }

    boolean recordFailure(String artifact, long expected, String code) {
        return jdbc.sql("UPDATE " + table + """
                 SET last_error_code = :failureCode
                 WHERE artifact = :artifact
                   AND required_generation = :expected
                   AND projected_generation < required_generation
                """)
                .param("failureCode", code).param("artifact", artifact)
                .param("expected", expected).update() == 1;
    }
}
