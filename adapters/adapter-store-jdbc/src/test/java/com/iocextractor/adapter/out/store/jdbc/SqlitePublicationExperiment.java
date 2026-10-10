package com.iocextractor.adapter.out.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Isolated CAP-7C physical visibility probe. Not a canonical writer or production schema.
 * Callers own connections, admission, safe time and transactions; payloads have no business semantics.
 * Pending contributions fold until seal; immutable chunk journals make their exact replay a no-op.
 */
final class SqlitePublicationExperiment {
    static final int CHUNK_FACT_LIMIT = 256;
    private static final int CHUNK_BYTE_LIMIT = 1024 * 1024;
    static final String LOOKUP_SQL = """
            SELECT f.payload FROM experiment_fact f
            JOIN experiment_unit u ON u.id=f.unit_id AND u.fence=f.fence
            WHERE f.family=? AND f.scope=? AND f.fact_key=? AND u.state='COMMITTED'
            ORDER BY f.stamp DESC LIMIT 1
            """;

    record Attempt(long unitId, long fence, long stamp) { }
    record Fact(String family, String scope, String key, String payload) { }
    record Receipt(long sequence, long effectiveMillis, String value) { }
    record Manifest(int chunks, int contributions, long minimumDeadline, Map<String, Long> dependencies) {
        Manifest { dependencies = Map.copyOf(dependencies); }
    }

    void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String sql : List.of(
                    """
                    CREATE TABLE experiment_unit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, identity TEXT NOT NULL UNIQUE,
                        pins TEXT NOT NULL, fence INTEGER NOT NULL DEFAULT 1,
                        state TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK(state IN ('PENDING','SEALED','COMMITTED','CANCELLED')),
                        expected_chunks INTEGER NOT NULL CHECK(expected_chunks>=0),
                        expected_contributions INTEGER NOT NULL CHECK(expected_contributions>=0),
                        next_chunk INTEGER NOT NULL DEFAULT 0, contributions INTEGER NOT NULL DEFAULT 0,
                        minimum_deadline INTEGER NOT NULL,
                        publication_sequence INTEGER UNIQUE, effective_millis INTEGER, receipt TEXT,
                        CHECK((expected_chunks=0)=(expected_contributions=0)),
                        CHECK((state='COMMITTED' AND publication_sequence IS NOT NULL
                            AND effective_millis IS NOT NULL AND receipt IS NOT NULL)
                            OR (state<>'COMMITTED' AND publication_sequence IS NULL
                            AND effective_millis IS NULL AND receipt IS NULL)))
                    """,
                    """
                    CREATE TABLE experiment_attempt (
                        stamp INTEGER PRIMARY KEY AUTOINCREMENT,
                        unit_id INTEGER NOT NULL REFERENCES experiment_unit(id), fence INTEGER NOT NULL,
                        UNIQUE(unit_id,fence), UNIQUE(unit_id,fence,stamp))
                    """,
                    """
                    CREATE TABLE experiment_fact (
                        unit_id INTEGER NOT NULL REFERENCES experiment_unit(id), fence INTEGER NOT NULL,
                        stamp INTEGER NOT NULL,
                        family TEXT NOT NULL, scope TEXT NOT NULL, fact_key TEXT NOT NULL, payload TEXT,
                        PRIMARY KEY(unit_id,fence,family,scope,fact_key),
                        FOREIGN KEY(unit_id,fence,stamp) REFERENCES experiment_attempt(unit_id,fence,stamp),
                        FOREIGN KEY(unit_id,fence,scope)
                            REFERENCES experiment_dependency(unit_id,fence,scope)) WITHOUT ROWID
                    """,
                    "CREATE INDEX experiment_fact_lookup ON experiment_fact(family,scope,fact_key,stamp DESC)",
                    """
                    CREATE TABLE experiment_chunk (
                        unit_id INTEGER NOT NULL REFERENCES experiment_unit(id), fence INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL, digest TEXT NOT NULL,
                        PRIMARY KEY(unit_id,fence,ordinal)) WITHOUT ROWID
                    """,
                    "CREATE TABLE experiment_epoch (scope TEXT PRIMARY KEY, version INTEGER NOT NULL) WITHOUT ROWID",
                    """
                    CREATE TABLE experiment_dependency (
                        unit_id INTEGER NOT NULL REFERENCES experiment_unit(id), fence INTEGER NOT NULL,
                        scope TEXT NOT NULL, version INTEGER NOT NULL,
                        PRIMARY KEY(unit_id,fence,scope)) WITHOUT ROWID
                    """,
                    "CREATE TABLE experiment_sequence (id INTEGER PRIMARY KEY CHECK(id=1), value INTEGER NOT NULL)",
                    "INSERT INTO experiment_sequence VALUES(1,0)")) {
                statement.execute(sql);
            }
        }
    }

    Attempt begin(Connection connection, String identity, String pins, Manifest manifest) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO experiment_unit(identity,pins,expected_chunks,expected_contributions,minimum_deadline)
                VALUES(?,?,?,?,?) RETURNING id
                """)) {
            statement.setString(1, identity);
            statement.setString(2, pins);
            statement.setInt(3, manifest.chunks());
            statement.setInt(4, manifest.contributions());
            statement.setLong(5, manifest.minimumDeadline());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("Missing experiment unit identity"); }
                Attempt attempt = allocateAttempt(connection, result.getLong(1), 1);
                pinDependencies(connection, attempt, manifest.dependencies());
                return attempt;
            }
        }
    }

    Attempt rebuild(Connection connection, Attempt previous, String pins, Manifest manifest) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_unit SET fence=fence+1,state='PENDING',next_chunk=0,contributions=0,
                    minimum_deadline=?,expected_chunks=?,expected_contributions=?
                WHERE id=? AND fence=? AND pins=? AND state IN ('PENDING','SEALED') RETURNING fence
                """)) {
            statement.setLong(1, manifest.minimumDeadline());
            statement.setInt(2, manifest.chunks());
            statement.setInt(3, manifest.contributions());
            statement.setLong(4, previous.unitId());
            statement.setLong(5, previous.fence());
            statement.setString(6, pins);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("Stale attempt or changed pins"); }
                Attempt next = allocateAttempt(connection, previous.unitId(), result.getLong(1));
                pinDependencies(connection, next, manifest.dependencies());
                return next;
            }
        }
    }

    void append(Connection connection, Attempt attempt, int ordinal, List<Fact> facts) throws SQLException {
        requireTransaction(connection);
        requireState(connection, attempt, "PENDING");
        if (facts.isEmpty() || facts.size() > CHUNK_FACT_LIMIT) {
            throw new IllegalArgumentException("Experiment chunk fact limit");
        }
        facts = List.copyOf(facts);
        String digest = digest(facts);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT digest FROM experiment_chunk WHERE unit_id=? AND fence=? AND ordinal=?
                """)) {
            bindAttempt(statement, attempt);
            statement.setInt(3, ordinal);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!digest.equals(result.getString(1))) { throw new SQLException("Changed chunk retry"); }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_unit SET next_chunk=next_chunk+1,contributions=contributions+?
                WHERE id=? AND fence=? AND next_chunk=? AND next_chunk<expected_chunks
                    AND contributions+?<=expected_contributions
                """)) {
            statement.setInt(1, facts.size());
            statement.setLong(2, attempt.unitId());
            statement.setLong(3, attempt.fence());
            statement.setInt(4, ordinal);
            statement.setInt(5, facts.size());
            requireOne(statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO experiment_fact VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(unit_id,fence,family,scope,fact_key)
                DO UPDATE SET payload=excluded.payload
                """)) {
            for (Fact fact : facts) {
                bindAttempt(statement, attempt);
                statement.setLong(3, attempt.stamp());
                statement.setString(4, fact.family());
                statement.setString(5, fact.scope());
                statement.setString(6, fact.key());
                statement.setString(7, fact.payload());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO experiment_chunk VALUES(?,?,?,?)")) {
            bindAttempt(statement, attempt);
            statement.setInt(3, ordinal);
            statement.setString(4, digest);
            statement.executeUpdate();
        }
    }

    void seal(Connection connection, Attempt attempt) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_unit SET state='SEALED' WHERE id=? AND fence=? AND state='PENDING'
                    AND next_chunk=expected_chunks AND contributions=expected_contributions
                """)) {
            bindAttempt(statement, attempt);
            requireOne(statement.executeUpdate());
        }
    }

    Receipt publish(Connection connection, Attempt attempt, long effectiveMillis, String receipt) throws SQLException {
        requireTransaction(connection);
        Optional<Receipt> committed = receipt(connection, attempt.unitId());
        if (committed.isPresent()) {
            requireState(connection, attempt, "COMMITTED");
            if (!committed.get().value().equals(receipt)) { throw new SQLException("Receipt identity mismatch"); }
            return committed.get();
        }
        requireState(connection, attempt, "SEALED");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM experiment_dependency d
                LEFT JOIN experiment_epoch e ON e.scope=d.scope
                WHERE d.unit_id=? AND d.fence=? AND d.version<>COALESCE(e.version,0) LIMIT 1
                """)) {
            bindAttempt(statement, attempt);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) { throw new SQLException("Dependency changed; rebuild required"); }
            }
        }
        long sequence;
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "UPDATE experiment_sequence SET value=value+1 WHERE id=1 RETURNING value")) {
            if (!result.next()) { throw new SQLException("Missing publication sequence"); }
            sequence = result.getLong(1);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_unit SET state='COMMITTED',publication_sequence=?,effective_millis=?,receipt=?
                WHERE id=? AND fence=? AND state='SEALED' AND minimum_deadline>?
                """)) {
            statement.setLong(1, sequence);
            statement.setLong(2, effectiveMillis);
            statement.setString(3, receipt);
            statement.setLong(4, attempt.unitId());
            statement.setLong(5, attempt.fence());
            statement.setLong(6, effectiveMillis);
            requireOne(statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_epoch SET version=version+1
                WHERE scope IN (SELECT scope FROM experiment_dependency WHERE unit_id=? AND fence=?)
                """)) {
            bindAttempt(statement, attempt);
            statement.executeUpdate();
        }
        return new Receipt(sequence, effectiveMillis, receipt);
    }

    void cancel(Connection connection, Attempt attempt) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE experiment_unit SET state='CANCELLED'
                WHERE id=? AND fence=? AND state IN ('PENDING','SEALED')
                """)) {
            bindAttempt(statement, attempt);
            requireOne(statement.executeUpdate());
        }
    }

    Optional<String> read(Connection connection, String family, String scope, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOOKUP_SQL)) {
            statement.setString(1, family);
            statement.setString(2, scope);
            statement.setString(3, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
        }
    }

    Optional<Receipt> receipt(Connection connection, long unitId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT publication_sequence,effective_millis,receipt
                FROM experiment_unit WHERE id=? AND state='COMMITTED'
                """)) {
            statement.setLong(1, unitId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new Receipt(result.getLong(1), result.getLong(2),
                        result.getString(3))) : Optional.empty();
            }
        }
    }

    /** Own pending facts overlay the caller's pinned committed base, including tombstones. */
    Optional<String> readOwn(Connection pending, Connection base, Attempt attempt,
                             String family, String scope, String key) throws SQLException {
        requireTransaction(base);
        try (PreparedStatement statement = pending.prepareStatement("""
                SELECT f.payload FROM experiment_fact f JOIN experiment_unit u
                    ON u.id=f.unit_id AND u.fence=f.fence
                WHERE f.unit_id=? AND f.fence=? AND f.family=? AND f.scope=? AND f.fact_key=?
                    AND u.state IN ('PENDING','SEALED')
                """)) {
            bindAttempt(statement, attempt);
            statement.setString(3, family);
            statement.setString(4, scope);
            statement.setString(5, key);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) { return Optional.ofNullable(result.getString(1)); }
            }
        }
        requirePendingAttempt(pending, attempt);
        return read(base, family, scope, key);
    }

    void advanceEpoch(Connection connection, String scope) throws SQLException {
        requireTransaction(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO experiment_epoch VALUES(?,1)
                ON CONFLICT(scope) DO UPDATE SET version=version+1
                """)) {
            statement.setString(1, scope);
            statement.executeUpdate();
        }
    }

    private void pinDependencies(Connection connection, Attempt attempt, Map<String, Long> dependencies)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO experiment_dependency VALUES(?,?,?,?)")) {
            for (var dependency : dependencies.entrySet()) {
                try (PreparedStatement epoch = connection.prepareStatement(
                        "INSERT INTO experiment_epoch VALUES(?,0) ON CONFLICT(scope) DO NOTHING")) {
                    epoch.setString(1, dependency.getKey());
                    epoch.executeUpdate();
                }
                try (PreparedStatement epoch = connection.prepareStatement(
                        "SELECT version FROM experiment_epoch WHERE scope=?")) {
                    epoch.setString(1, dependency.getKey());
                    try (ResultSet result = epoch.executeQuery()) {
                        if (!result.next() || result.getLong(1) != dependency.getValue()) {
                            throw new SQLException("Stale dependency snapshot");
                        }
                    }
                }
                bindAttempt(statement, attempt);
                statement.setString(3, dependency.getKey());
                statement.setLong(4, dependency.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Attempt allocateAttempt(Connection connection, long unitId, long fence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO experiment_attempt(unit_id,fence) VALUES(?,?) RETURNING stamp")) {
            statement.setLong(1, unitId);
            statement.setLong(2, fence);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("Missing attempt stamp"); }
                return new Attempt(unitId, fence, result.getLong(1));
            }
        }
    }

    private void requirePendingAttempt(Connection connection, Attempt attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM experiment_unit WHERE id=? AND fence=? AND state IN ('PENDING','SEALED')")) {
            bindAttempt(statement, attempt);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("Stale attempt or invalid publication state"); }
            }
        }
    }

    private void requireState(Connection connection, Attempt attempt, String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM experiment_unit WHERE id=? AND fence=? AND state=?")) {
            bindAttempt(statement, attempt);
            statement.setString(3, state);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) { throw new SQLException("Stale attempt or invalid publication state"); }
            }
        }
    }

    private static void bindAttempt(PreparedStatement statement, Attempt attempt) throws SQLException {
        statement.setLong(1, attempt.unitId());
        statement.setLong(2, attempt.fence());
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) { throw new IllegalArgumentException("Caller-owned transaction required"); }
    }

    private static void requireOne(int changed) throws SQLException {
        if (changed != 1) { throw new SQLException("Incomplete, expired or stale publication attempt"); }
    }

    private static String digest(List<Fact> facts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int bytes = 0;
            for (Fact fact : facts) {
                for (String value : new String[]{fact.family(), fact.scope(), fact.key(), fact.payload()}) {
                    if (value != null && value.length() > CHUNK_BYTE_LIMIT - bytes - Integer.BYTES) {
                        throw new IllegalArgumentException("Experiment chunk byte limit");
                    }
                    byte[] encoded = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                    bytes += encoded.length + Integer.BYTES;
                    if (bytes > CHUNK_BYTE_LIMIT) { throw new IllegalArgumentException("Experiment chunk byte limit"); }
                    int length = value == null ? -1 : encoded.length;
                    for (int shift = 24; shift >= 0; shift -= 8) { digest.update((byte) (length >>> shift)); }
                    digest.update(encoded);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
