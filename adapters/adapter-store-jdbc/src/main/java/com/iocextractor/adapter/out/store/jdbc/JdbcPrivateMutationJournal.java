package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.CanonicalRecordMutationOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-confined typed effect journal in one private preparation database.
 * Its rows commit/rollback with the native mutation, not with canonical publication.
 * The caller owns the connection, transaction and unit pins. Chunk readers require
 * one sealed read snapshot; this class neither seals nor publishes a unit.
 */
final class JdbcPrivateMutationJournal implements JdbcMutationEffectSink {
    private static final int CHUNK_LIMIT = 256;
    private final PreparedStatement insert;
    private final Thread owner = Thread.currentThread();

    record Entry(long ordinal, String artifact, long rowId, long lifecycleId, boolean renewsValidity) { }
    record Chunk(long lastOrdinal, int count) { }

    private JdbcPrivateMutationJournal(Connection connection) throws SQLException {
        insert = connection.prepareStatement("""
                INSERT INTO private_mutation_effect(artifact,canonical_row_id,lifecycle_id,renew_validity)
                VALUES (?,?,?,?) ON CONFLICT(artifact,canonical_row_id) DO UPDATE SET
                    renew_validity=MAX(private_mutation_effect.renew_validity,excluded.renew_validity)
                WHERE private_mutation_effect.lifecycle_id=excluded.lifecycle_id RETURNING ordinal
                """);
    }

    /** Opens a pinned journal inside an already-owned private native transaction. */
    static JdbcPrivateMutationJournal open(Connection c, String unit, String pins) throws SQLException {
        requireTransaction(c);
        requireText(unit, "unit");
        requireText(pins, "pins");
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS private_mutation_unit(singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),"
                    + "unit TEXT NOT NULL,pins TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS private_mutation_effect(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "artifact TEXT NOT NULL,canonical_row_id INTEGER NOT NULL,lifecycle_id INTEGER NOT NULL CHECK(lifecycle_id>0),"
                    + "renew_validity INTEGER NOT NULL CHECK(renew_validity IN (0,1)),UNIQUE(artifact,canonical_row_id))");
        }
        try (var s = c.prepareStatement("INSERT INTO private_mutation_unit VALUES(1,?,?) ON CONFLICT(singleton_id) DO NOTHING")) {
            s.setString(1, unit);
            s.setString(2, pins);
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("SELECT 1 FROM private_mutation_unit WHERE singleton_id=1 AND unit=? AND pins=?")) {
            s.setString(1, unit);
            s.setString(2, pins);
            try (var rows = s.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Private mutation journal unit or pins changed"); }
            }
        }
        return new JdbcPrivateMutationJournal(c);
    }

    @Override
    public void record(String artifact, CanonicalRecordMutationOutcome outcome, boolean renewsValidity) throws SQLException {
        requireOwner();
        requireTransaction(insert.getConnection());
        requireText(artifact, "artifact");
        Objects.requireNonNull(outcome, "outcome");
        insert.setString(1, artifact);
        insert.setLong(2, outcome.canonicalRowId());
        insert.setLong(3, outcome.lifecycleId());
        insert.setInt(4, renewsValidity ? 1 : 0);
        try (var rows = insert.executeQuery()) {
            if (!rows.next()) { throw new SQLException("Private mutation journal lifecycle identity changed"); }
        }
    }

    /** Streams an indexed, bounded page without owning or closing the read snapshot. */
    static Chunk readChunk(Connection c, long afterOrdinal, int limit, Consumer<Entry> consumer) throws SQLException {
        requireTransaction(c);
        Objects.requireNonNull(consumer, "consumer");
        if (afterOrdinal < 0 || limit < 1 || limit > CHUNK_LIMIT) {
            throw new IllegalArgumentException("Private mutation journal chunk bound");
        }
        long last = afterOrdinal;
        int count = 0;
        try (var s = c.prepareStatement("SELECT ordinal,artifact,canonical_row_id,lifecycle_id,renew_validity"
                + " FROM private_mutation_effect WHERE ordinal>? ORDER BY ordinal LIMIT ?")) {
            s.setLong(1, afterOrdinal);
            s.setInt(2, limit);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    last = rows.getLong(1);
                    consumer.accept(new Entry(last, rows.getString(2), rows.getLong(3), rows.getLong(4), rows.getInt(5) == 1));
                    count++;
                }
            }
        }
        return new Chunk(last, count);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " must not be blank"); }
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) { throw new SQLException("Private mutation journal requires an owned transaction"); }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) { throw new IllegalStateException("Private mutation journal belongs to another thread"); }
    }

    @Override public void close() throws SQLException {
        requireOwner();
        insert.close();
    }
}
