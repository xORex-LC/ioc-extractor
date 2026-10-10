package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.ArtifactIdReservation;
import com.iocextractor.common.IocExtractorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;

import static com.iocextractor.adapter.out.store.jdbc.JdbcSql.quote;

/**
 * Initialization of an id-less artifact's internal reservation space.
 * Reuses the real durable range allocator; no second allocation transition.
 * The staged backend owns the namespace exclusively: mixing native AUTOINCREMENT
 * writers with independently reserved ranges is explicitly unsupported.
 */
final class JdbcPrivateIdReservations {
    private final DataSource canonical;
    private final Clock clock;
    private final JdbcWriterAdmission admission;
    private final JdbcArtifactIdAllocator ranges;

    JdbcPrivateIdReservations(DataSource canonical, Clock clock, JdbcWriterAdmission admission) {
        this.canonical = canonical;
        this.clock = clock;
        this.admission = admission;
        ranges = new JdbcArtifactIdAllocator(canonical, clock);
    }

    /** Pins active IDs, former row IDs, deleted AUTOINCREMENT IDs and burned reservations. */
    void initialize(String artifact, long identityEpoch) {
        String name = DataframeColumn.requireSqlIdentifier(artifact, "internal artifact");
        initialize(name, identityEpoch, "SELECT MAX(value) FROM (SELECT MAX(id) AS value FROM "
                + quote(name) + " UNION ALL SELECT MAX(former_row_id) FROM " + quote(name + "_history")
                + " UNION ALL SELECT seq FROM sqlite_sequence WHERE name=?)");
    }

    /** History has a distinct internal sequence; former_row_id is not its identity. */
    void initializeHistory(String artifact, long identityEpoch) {
        String name = DataframeColumn.requireSqlIdentifier(artifact, "internal artifact") + "_history";
        initialize(name, identityEpoch, "SELECT MAX(value) FROM (SELECT MAX(history_id) AS value FROM "
                + quote(name) + " UNION ALL SELECT seq FROM sqlite_sequence WHERE name=?)");
    }

    private void initialize(String name, long identityEpoch, String highWaterSql) {
        admission.execute(JdbcWriterAdmission.OperationClass.CONTROL, () -> {
            try (var c = canonical.getConnection()) {
                c.setAutoCommit(false);
                try {
                    long extreme;
                    try (var select = c.prepareStatement(highWaterSql)) {
                        select.setString(1, name);
                        try (var rows = select.executeQuery()) {
                            if (!rows.next()) { throw new SQLException("Missing internal sequence high-water"); }
                            extreme = rows.getLong(1);
                        }
                    }
                    long next = Math.incrementExact(Math.max(0, extreme));
                    try (var insert = c.prepareStatement("""
                            INSERT INTO artifact_id_allocator(artifact,strategy,next_value,identity_epoch,updated_at_ms)
                            VALUES (?,'ASCENDING',?,?,?) ON CONFLICT(artifact) DO NOTHING
                            """)) {
                        insert.setString(1, name);
                        insert.setLong(2, next);
                        insert.setLong(3, identityEpoch);
                        insert.setLong(4, clock.millis());
                        insert.executeUpdate();
                    }
                    try (var state = c.prepareStatement(
                            "SELECT strategy,identity_epoch FROM artifact_id_allocator WHERE artifact=?")) {
                        state.setString(1, name);
                        try (var rows = state.executeQuery()) {
                            if (!rows.next() || !"ASCENDING".equals(rows.getString(1))
                                    || rows.getLong(2) != identityEpoch) {
                                throw new SQLException("Internal allocator identity drift");
                            }
                        }
                    }
                    try (var update = c.prepareStatement("""
                            UPDATE artifact_id_allocator SET next_value=MAX(next_value,?),updated_at_ms=?
                            WHERE artifact=?
                            """)) {
                        update.setLong(1, next);
                        update.setLong(2, clock.millis());
                        update.setString(3, name);
                        update.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException | RuntimeException | Error failure) {
                    try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IocExtractorException("Internal reservation authority failed", failure);
            }
            return null;
        });
    }

    ArtifactIdReservation reserve(String artifact, int count) {
        return admission.execute(JdbcWriterAdmission.OperationClass.CONTROL, () -> ranges.reserve(artifact, count));
    }

    /** Seeds only the private working sequence, never the authoritative namespace. */
    static void seedPrivateSequence(Connection working, String artifact, ArtifactIdReservation reservation)
            throws SQLException {
        if (working.getAutoCommit()) { throw new SQLException("Private sequence requires an owned transaction"); }
        if (reservation.count() < 1) { throw new SQLException("Private insertion needs a non-empty reservation"); }
        String name = DataframeColumn.requireSqlIdentifier(artifact, "internal artifact");
        long floor = Math.decrementExact(reservation.idAt(0));
        try (var read = working.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")) {
            read.setString(1, name);
            try (var rows = read.executeQuery()) {
                if (rows.next() && rows.getLong(1) > floor) {
                    throw new SQLException("Private sequence exceeds reserved range");
                }
            }
        }
        try (var delete = working.prepareStatement("DELETE FROM sqlite_sequence WHERE name=?");
             var insert = working.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES (?,?)")) {
            delete.setString(1, name);
            delete.executeUpdate();
            insert.setString(1, name);
            insert.setLong(2, floor);
            insert.executeUpdate();
        }
    }
}
