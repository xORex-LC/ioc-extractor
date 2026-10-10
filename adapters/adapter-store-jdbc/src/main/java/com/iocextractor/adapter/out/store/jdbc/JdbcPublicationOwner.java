package com.iocextractor.adapter.out.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/** Durable logical ownership, independent of physical SQLite writer admission. */
final class JdbcPublicationOwner {
    private static final int ATTEMPT_LIMIT = 3;
    record Owner(String unit, long fence) { }

    void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE publication_logical_owner(singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),"
                    + "unit TEXT,fence INTEGER NOT NULL,attempts INTEGER NOT NULL CHECK(attempts BETWEEN 0 AND 3))");
            s.executeUpdate("INSERT INTO publication_logical_owner VALUES(1,NULL,0,0)");
            s.execute("CREATE TABLE publication_owner_failure(fence INTEGER PRIMARY KEY,unit TEXT NOT NULL,"
                    + "failed_at_ms INTEGER NOT NULL,error_code TEXT NOT NULL)");
        }
    }

    long fence(Connection c) throws SQLException {
        try (var s = c.createStatement(); var rows = s.executeQuery(
                "SELECT fence FROM publication_logical_owner WHERE singleton_id=1")) {
            if (!rows.next()) { throw new SQLException("Missing logical publication ownership"); }
            return rows.getLong(1);
        }
    }

    void fail(Connection c, Owner owner, long asOf, String code) throws SQLException {
        require(c, owner);
        try (var s = c.prepareStatement("INSERT INTO publication_owner_failure VALUES(?,?,?,?) ON CONFLICT(fence) DO NOTHING")) {
            s.setLong(1, owner.fence()); s.setString(2, owner.unit()); s.setLong(3, asOf); s.setString(4, code);
            s.executeUpdate();
        }
        release(c, owner);
    }

    /** Records the abandoned eligible claim before its retry starts a fresh fenced budget. */
    void recordReclaim(Connection c, String unit, long asOf) throws SQLException {
        requireTransaction(c);
        try (var statement = c.prepareStatement("INSERT INTO publication_owner_failure(fence,unit,failed_at_ms,error_code)"
                + " SELECT fence,unit,?,'PUBLICATION.ABANDONED_CLAIM' FROM publication_logical_owner"
                + " WHERE singleton_id=1 AND unit=? ON CONFLICT(fence) DO NOTHING")) {
            statement.setLong(1, asOf); statement.setString(2, unit); statement.executeUpdate();
        }
    }

    /** A receipt wins over an abandoned owner after commit; never release an uncommitted different unit. */
    void releaseCommitted(Connection c) throws SQLException {
        requireTransaction(c);
        try (var s = c.prepareStatement("UPDATE publication_logical_owner SET unit=NULL,fence=fence+1"
                + " WHERE singleton_id=1 AND unit IN(SELECT identity FROM publication_unit WHERE state='COMMITTED')")) {
            s.executeUpdate();
        }
    }

    /** Caller supplies the ordered eligible unit and the durable fence it read. */
    Owner acquire(Connection c, String unit, long expectedFence) throws SQLException {
        requireTransaction(c);
        try (var s = c.prepareStatement("UPDATE publication_logical_owner SET unit=?,fence=fence+1,attempts=0"
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
        try (var s = c.prepareStatement("SELECT 1 FROM publication_logical_owner WHERE singleton_id=1 AND unit=? AND fence=?")) {
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
        try (var s = c.prepareStatement("UPDATE publication_logical_owner SET attempts=attempts+1"
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
        try (var s = c.prepareStatement("UPDATE publication_logical_owner SET unit=NULL WHERE singleton_id=1 AND fence=?")) {
            s.setLong(1, owner.fence());
            if (s.executeUpdate() != 1) { throw new SQLException("Logical promotion release failed"); }
        }
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) { throw new SQLException("Logical ownership requires an owned transaction"); }
    }
}
