package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportPlan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/**
 * Transaction-local JDBC implementation of stable sparse reusable export slots.
 *
 * <p>The registry is reconciled in a short write transaction. A later read
 * transaction verifies the captured canonical projection generation before any
 * rows are exposed to an export consumer.</p>
 */
final class JdbcExportSlotRegistry {

    private static final String EXTERNAL_ID = "id";

    boolean appliesTo(ExportArtifactSpec artifact) {
        return artifact.columns().contains(EXTERNAL_ID);
    }

    void reconcile(Connection connection, ExportPlan plan, Instant asOf) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(asOf, "asOf");
        for (ExportArtifactSpec artifact : plan.artifacts()) {
            if (appliesTo(artifact)) {
                reconcile(connection, plan.profile().name(), artifact.artifactName(), asOf);
            }
        }
    }

    void requireCurrentSnapshot(Connection connection,
                                String profile,
                                String artifact,
                                Instant asOf) throws SQLException {
        long sourceGeneration = currentGeneration(connection, artifact);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT policy_version, source_generation
                FROM export_slot_state
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SnapshotChangedException(
                            "Export-slot state is missing for " + profile + "/" + artifact);
                }
                requirePolicyVersion(resultSet.getString("policy_version"), profile, artifact);
                long resolvedGeneration = resultSet.getLong("source_generation");
                if (resolvedGeneration != sourceGeneration) {
                    throw new SnapshotChangedException(
                            "Canonical generation changed for " + profile + "/" + artifact);
                }
            }
        }
        requireCompleteAssignments(connection, profile, artifact, asOf);
    }

    long upperSlot(Connection connection,
                   String profile,
                   String artifact,
                   Instant asOf) throws SQLException {
        String sql = """
                SELECT COALESCE(MAX(a.slot), 0)
                FROM ${artifact} t
                JOIN export_slot_assignment a
                  ON a.profile = ?
                 AND a.artifact = ?
                 AND a.lifecycle_id = t.${lifecycleId}
                WHERE t.${validUntil} > ?
                """
                .replace("${artifact}", quote(artifact))
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, asOf.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Export-slot coverage query returned no row for " + artifact);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private void reconcile(Connection connection,
                           String profile,
                           String artifact,
                           Instant asOf) throws SQLException {
        long generation = currentGeneration(connection, artifact);
        if (initializeState(connection, profile, artifact, asOf)) {
            seedActiveAssignments(connection, profile, artifact, generation, asOf);
        } else {
            requireStoredPolicyVersion(connection, profile, artifact);
        }
        releaseVanishedAssignments(connection, profile, artifact, asOf);
        assignFreeSlots(connection, profile, artifact, asOf);
        int highWaterAssignments = assignHighWaterSlots(connection, profile, artifact, asOf);
        removeConsumedFreeSlots(connection, profile, artifact);
        advanceState(connection, profile, artifact, generation, highWaterAssignments, asOf);
        requireCompleteAssignments(connection, profile, artifact, asOf);
    }

    private boolean initializeState(Connection connection,
                                    String profile,
                                    String artifact,
                                    Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO export_slot_state(
                    profile, artifact, policy_version, next_slot,
                    source_generation, updated_at_ms)
                VALUES (?, ?, ?, 1, 0, ?)
                ON CONFLICT(profile, artifact) DO NOTHING
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setString(3, ExportPlan.EXPORT_SLOT_POLICY_VERSION);
            statement.setLong(4, asOf.toEpochMilli());
            return statement.executeUpdate() == 1;
        }
    }

    private void seedActiveAssignments(Connection connection,
                                       String profile,
                                       String artifact,
                                       long generation,
                                       Instant asOf) throws SQLException {
        requireSeedableRows(connection, artifact, asOf);
        String insert = """
                INSERT INTO export_slot_assignment(
                    profile, artifact, lifecycle_id, slot, assigned_at_ms)
                SELECT ?, ?, t.${lifecycleId}, t.${externalId}, ?
                FROM ${artifact} t
                WHERE t.${validUntil} > ?
                ORDER BY t.${lifecycleId}
                """
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${externalId}", quote("id"))
                .replace("${artifact}", quote(artifact))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, asOf.toEpochMilli());
            statement.setLong(4, asOf.toEpochMilli());
            statement.executeUpdate();
        }

        long nextSlot = nextSlotAfterAssignments(connection, profile, artifact);
        materializeSeedHoles(connection, profile, artifact, nextSlot, asOf);
        updateState(connection, profile, artifact, generation, nextSlot, asOf);
    }

    private void requireSeedableRows(Connection connection,
                                     String artifact,
                                     Instant asOf) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact)
                + " WHERE " + quote("_valid_until_epoch_ms") + " > ?"
                + " AND (" + quote("_lifecycle_id") + " IS NULL"
                + " OR " + quote("_lifecycle_id") + " <= 0"
                + " OR " + quote("id") + " <= 0) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    throw new SQLException(
                            "Cannot seed positive export slots from active rows in " + artifact);
                }
            }
        }
    }

    private long nextSlotAfterAssignments(Connection connection,
                                          String profile,
                                          String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(slot), 0)
                FROM export_slot_assignment
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Export-slot seed query returned no row for " + artifact);
                }
                long maximum = resultSet.getLong(1);
                if (maximum == Long.MAX_VALUE) {
                    throw new SQLException("Export-slot space is exhausted for " + profile + "/" + artifact);
                }
                return maximum + 1;
            }
        }
    }

    private void materializeSeedHoles(Connection connection,
                                      String profile,
                                      String artifact,
                                      long nextSlot,
                                      Instant asOf) throws SQLException {
        if (nextSlot <= 1) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH RECURSIVE candidate(slot) AS (
                    SELECT 1
                    UNION ALL
                    SELECT slot + 1 FROM candidate WHERE slot + 1 < ?
                )
                INSERT INTO export_slot_free(profile, artifact, slot, released_at_ms)
                SELECT ?, ?, candidate.slot, ?
                FROM candidate
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM export_slot_assignment a
                    WHERE a.profile = ?
                      AND a.artifact = ?
                      AND a.slot = candidate.slot)
                """)) {
            statement.setLong(1, nextSlot);
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.setLong(4, asOf.toEpochMilli());
            statement.setString(5, profile);
            statement.setString(6, artifact);
            statement.executeUpdate();
        }
    }

    private void releaseVanishedAssignments(Connection connection,
                                             String profile,
                                             String artifact,
                                             Instant asOf) throws SQLException {
        String activeLifecycle = "SELECT 1 FROM " + quote(artifact) + " t"
                + " WHERE t." + quote("_lifecycle_id") + " = a.lifecycle_id"
                + " AND t." + quote("_valid_until_epoch_ms") + " > ?";
        String insertFree = """
                INSERT INTO export_slot_free(profile, artifact, slot, released_at_ms)
                SELECT a.profile, a.artifact, a.slot, ?
                FROM export_slot_assignment a
                WHERE a.profile = ? AND a.artifact = ?
                  AND NOT EXISTS (${activeLifecycle})
                ON CONFLICT(profile, artifact, slot) DO UPDATE SET
                    released_at_ms = excluded.released_at_ms
                """.replace("${activeLifecycle}", activeLifecycle);
        try (PreparedStatement statement = connection.prepareStatement(insertFree)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.setLong(4, asOf.toEpochMilli());
            statement.executeUpdate();
        }

        String delete = "DELETE FROM export_slot_assignment AS a"
                + " WHERE a.profile = ? AND a.artifact = ?"
                + " AND NOT EXISTS (" + activeLifecycle + ")";
        try (PreparedStatement statement = connection.prepareStatement(delete)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, asOf.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void assignFreeSlots(Connection connection,
                                 String profile,
                                 String artifact,
                                 Instant asOf) throws SQLException {
        String sql = """
                WITH new_lifecycles AS (
                    SELECT t.${lifecycleId} AS lifecycle_id,
                           ROW_NUMBER() OVER (ORDER BY t.${lifecycleId}) AS ordinal
                    FROM ${artifact} t
                    WHERE t.${validUntil} > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM export_slot_assignment a
                          WHERE a.profile = ? AND a.artifact = ?
                            AND a.lifecycle_id = t.${lifecycleId})
                ),
                available_slots AS (
                    SELECT slot, ROW_NUMBER() OVER (ORDER BY slot) AS ordinal
                    FROM export_slot_free
                    WHERE profile = ? AND artifact = ?
                )
                INSERT INTO export_slot_assignment(
                    profile, artifact, lifecycle_id, slot, assigned_at_ms)
                SELECT ?, ?, n.lifecycle_id, f.slot, ?
                FROM new_lifecycles n
                JOIN available_slots f ON f.ordinal = n.ordinal
                """
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${artifact}", quote(artifact))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.setString(4, profile);
            statement.setString(5, artifact);
            statement.setString(6, profile);
            statement.setString(7, artifact);
            statement.setLong(8, asOf.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private int assignHighWaterSlots(Connection connection,
                                     String profile,
                                     String artifact,
                                     Instant asOf) throws SQLException {
        String sql = """
                WITH new_lifecycles AS (
                    SELECT t.${lifecycleId} AS lifecycle_id,
                           ROW_NUMBER() OVER (ORDER BY t.${lifecycleId}) AS ordinal
                    FROM ${artifact} t
                    WHERE t.${validUntil} > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM export_slot_assignment a
                          WHERE a.profile = ? AND a.artifact = ?
                            AND a.lifecycle_id = t.${lifecycleId})
                )
                INSERT INTO export_slot_assignment(
                    profile, artifact, lifecycle_id, slot, assigned_at_ms)
                SELECT ?, ?, n.lifecycle_id, s.next_slot + n.ordinal - 1, ?
                FROM new_lifecycles n
                JOIN export_slot_state s
                  ON s.profile = ? AND s.artifact = ?
                """
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${artifact}", quote(artifact))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.setString(4, profile);
            statement.setString(5, artifact);
            statement.setLong(6, asOf.toEpochMilli());
            statement.setString(7, profile);
            statement.setString(8, artifact);
            return statement.executeUpdate();
        }
    }

    private void removeConsumedFreeSlots(Connection connection,
                                         String profile,
                                         String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM export_slot_free AS f
                WHERE f.profile = ? AND f.artifact = ?
                  AND EXISTS (
                      SELECT 1 FROM export_slot_assignment a
                      WHERE a.profile = f.profile
                        AND a.artifact = f.artifact
                        AND a.slot = f.slot)
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.executeUpdate();
        }
    }

    private void advanceState(Connection connection,
                              String profile,
                              String artifact,
                              long generation,
                              int highWaterAssignments,
                              Instant asOf) throws SQLException {
        if (highWaterAssignments < 0) {
            throw new SQLException("Negative export-slot assignment count for " + profile + "/" + artifact);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE export_slot_state
                SET next_slot = next_slot + ?,
                    source_generation = ?,
                    updated_at_ms = ?
                WHERE profile = ? AND artifact = ?
                  AND next_slot <= ?
                """)) {
            statement.setInt(1, highWaterAssignments);
            statement.setLong(2, generation);
            statement.setLong(3, asOf.toEpochMilli());
            statement.setString(4, profile);
            statement.setString(5, artifact);
            statement.setLong(6, Long.MAX_VALUE - highWaterAssignments);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Export-slot space is exhausted for " + profile + "/" + artifact);
            }
        }
    }

    private void updateState(Connection connection,
                             String profile,
                             String artifact,
                             long generation,
                             long nextSlot,
                             Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE export_slot_state
                SET next_slot = CASE WHEN next_slot > ? THEN next_slot ELSE ? END,
                    source_generation = ?,
                    updated_at_ms = ?
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setLong(1, nextSlot);
            statement.setLong(2, nextSlot);
            statement.setLong(3, generation);
            statement.setLong(4, asOf.toEpochMilli());
            statement.setString(5, profile);
            statement.setString(6, artifact);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Export-slot state disappeared for " + profile + "/" + artifact);
            }
        }
    }

    private void requireStoredPolicyVersion(Connection connection,
                                            String profile,
                                            String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT policy_version
                FROM export_slot_state
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Export-slot state disappeared for " + profile + "/" + artifact);
                }
                requirePolicyVersion(resultSet.getString(1), profile, artifact);
            }
        }
    }

    private void requirePolicyVersion(String actual,
                                      String profile,
                                      String artifact) throws SQLException {
        if (!ExportPlan.EXPORT_SLOT_POLICY_VERSION.equals(actual)) {
            throw new SQLException("Export-slot policy mismatch for " + profile + "/" + artifact
                    + ": expected " + ExportPlan.EXPORT_SLOT_POLICY_VERSION + ", found " + actual);
        }
    }

    private void requireCompleteAssignments(Connection connection,
                                            String profile,
                                            String artifact,
                                            Instant asOf) throws SQLException {
        String sql = """
                SELECT COUNT(*) AS active_count,
                       COUNT(a.lifecycle_id) AS assigned_count
                FROM ${artifact} t
                LEFT JOIN export_slot_assignment a
                  ON a.profile = ?
                 AND a.artifact = ?
                 AND a.lifecycle_id = t.${lifecycleId}
                WHERE t.${validUntil} > ?
                """
                .replace("${artifact}", quote(artifact))
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, asOf.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || resultSet.getLong("active_count") != resultSet.getLong("assigned_count")) {
                    throw new SQLException(
                            "Export-slot assignments are incomplete for " + profile + "/" + artifact);
                }
            }
        }
    }

    private long currentGeneration(Connection connection, String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT required_generation
                FROM artifact_projection_state
                WHERE artifact = ?
                """)) {
            statement.setString(1, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static String quote(String identifier) {
        // SQLite cannot bind identifiers; validate the complete identifier before quoting it.
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    /** Signals a benign generation race that the snapshot reader may retry. */
    static final class SnapshotChangedException extends SQLException {

        private static final long serialVersionUID = 1L;

        SnapshotChangedException(String message) {
            super(message);
        }
    }
}
