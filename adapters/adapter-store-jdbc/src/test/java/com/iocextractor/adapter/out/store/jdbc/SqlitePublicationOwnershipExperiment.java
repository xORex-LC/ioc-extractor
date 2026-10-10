package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Test-only durable logical ownership, independent of physical SQLite writer admission. */
final class SqlitePublicationOwnershipExperiment {
    private static final int ATTEMPT_LIMIT = 3;
    record Owner(String unit, long fence) { }

    void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE experiment_logical_owner(singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),"
                    + "unit TEXT,fence INTEGER NOT NULL,attempts INTEGER NOT NULL CHECK(attempts BETWEEN 0 AND 3))");
            s.executeUpdate("INSERT INTO experiment_logical_owner VALUES(1,NULL,0,0)");
        }
    }

    /** Caller supplies the ordered eligible unit and the durable fence it read. */
    Owner acquire(Connection c, String unit, long expectedFence) throws SQLException {
        requireTransaction(c);
        try (var s = c.prepareStatement("UPDATE experiment_logical_owner SET unit=?,fence=fence+1,attempts=0"
                + " WHERE singleton_id=1 AND fence=? AND (unit IS NULL OR unit=?) RETURNING fence")) {
            s.setString(1, unit);
            s.setLong(2, expectedFence);
            s.setString(3, unit);
            try (var rows = s.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Logical promotion ownership changed or occupied"); }
                return new Owner(unit, rows.getLong(1));
            }
        }
    }

    void require(Connection c, Owner owner) throws SQLException {
        requireTransaction(c);
        try (var s = c.prepareStatement("SELECT 1 FROM experiment_logical_owner WHERE singleton_id=1 AND unit=? AND fence=?")) {
            s.setString(1, owner.unit());
            s.setLong(2, owner.fence());
            try (var rows = s.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Stale logical promotion owner"); }
            }
        }
    }

    /** Must commit before starting preparation, so a crashed attempt consumes its budget. */
    int nextAttempt(Connection c, Owner owner) throws SQLException {
        require(c, owner);
        try (var s = c.prepareStatement("UPDATE experiment_logical_owner SET attempts=attempts+1"
                + " WHERE singleton_id=1 AND unit=? AND fence=? AND attempts<? RETURNING attempts")) {
            s.setString(1, owner.unit());
            s.setLong(2, owner.fence());
            s.setInt(3, ATTEMPT_LIMIT);
            try (var rows = s.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Publication rebuild budget exhausted"); }
                return rows.getInt(1);
            }
        }
    }

    /** Caller has persisted completion or retryable failure before relinquishing ownership. */
    void release(Connection c, Owner owner) throws SQLException {
        require(c, owner);
        try (var s = c.prepareStatement("UPDATE experiment_logical_owner SET unit=NULL WHERE singleton_id=1 AND fence=?")) {
            s.setLong(1, owner.fence());
            if (s.executeUpdate() != 1) { throw new SQLException("Logical promotion release failed"); }
        }
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) { throw new SQLException("Logical ownership requires an owned transaction"); }
    }
}
