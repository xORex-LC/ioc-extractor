package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.dataframeimport.model.ImportExistingSlotPolicy;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportPlan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Connection-scoped JDBC registry for stable sparse reusable export slots.
 *
 * <p>Free space is stored as coalesced ranges. Allocation work is proportional
 * to affected lifecycles and ranges, never to the numeric value of a requested
 * slot. Callers own the connection transaction; export and import therefore use
 * the same allocator inside their respective canonical write boundary.</p>
 */
final class JdbcExportSlotRegistry {

    static final long MAX_ASSIGNABLE_SLOT = Long.MAX_VALUE - 1;

    private static final String EXTERNAL_ID = "id";
    private static final String EXCLUDED_LIFECYCLE = "temp_export_slot_exclusion";
    private static final String RELEASED_SLOT = "temp_export_slot_release";
    private static final String MERGED_RANGE = "temp_export_slot_merged_range";
    private static final String PENDING_LIFECYCLE = "temp_export_slot_pending";

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

    /**
     * Reconciles preferred import requests and all remaining active lifecycles.
     * Duplicate requests and strict survivor mismatches fail before any slot
     * mutation. The owning canonical transaction decides whether to roll back.
     */
    List<PreferredExportSlotResolution> reconcilePreferred(
            Connection connection,
            String profile,
            String artifact,
            Instant asOf,
            List<PreferredExportSlotRequest> requests) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(asOf, "asOf");
        List<PreferredExportSlotRequest> preferred = List.copyOf(
                Objects.requireNonNull(requests, "requests"));
        validatePreferredRequests(preferred);
        ensureScratchTables(connection);
        clearScratchTables(connection);
        try {
            excludeNewLifecycles(connection, preferred);
            long generation = currentGeneration(connection, artifact);
            initializeOrValidate(connection, profile, artifact, generation, asOf);
            releaseVanishedAssignments(connection, profile, artifact, asOf);
            validatePreferredSurvivors(connection, profile, artifact, preferred);

            List<PreferredExportSlotResolution> resolutions = new ArrayList<>(preferred.size());
            for (PreferredExportSlotRequest request : preferred) {
                resolutions.add(resolvePreferred(connection, profile, artifact, request, asOf));
            }
            assignRemainingActive(connection, profile, artifact, asOf);
            updateGeneration(connection, profile, artifact, generation, asOf);
            requireCompleteAssignments(connection, profile, artifact, asOf);
            return List.copyOf(resolutions);
        } finally {
            clearScratchTables(connection);
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
        ensureScratchTables(connection);
        clearScratchTables(connection);
        try {
            long generation = currentGeneration(connection, artifact);
            initializeOrValidate(connection, profile, artifact, generation, asOf);
            releaseVanishedAssignments(connection, profile, artifact, asOf);
            assignRemainingActive(connection, profile, artifact, asOf);
            updateGeneration(connection, profile, artifact, generation, asOf);
            requireCompleteAssignments(connection, profile, artifact, asOf);
        } finally {
            clearScratchTables(connection);
        }
    }

    private void initializeOrValidate(Connection connection,
                                      String profile,
                                      String artifact,
                                      long generation,
                                      Instant asOf) throws SQLException {
        if (initializeState(connection, profile, artifact, asOf)) {
            seedActiveAssignments(connection, profile, artifact, generation, asOf);
        } else {
            requireStoredPolicyVersion(connection, profile, artifact);
        }
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
                  AND NOT EXISTS (
                      SELECT 1 FROM ${excluded} e
                      WHERE e.lifecycle_id = t.${lifecycleId})
                ORDER BY t.${lifecycleId}
                """
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${externalId}", quote("id"))
                .replace("${artifact}", quote(artifact))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"))
                .replace("${excluded}", EXCLUDED_LIFECYCLE);
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, asOf.toEpochMilli());
            statement.setLong(4, asOf.toEpochMilli());
            statement.executeUpdate();
        }

        long nextSlot = nextSlotAfterAssignments(connection, profile, artifact);
        seedFreeRanges(connection, profile, artifact, asOf);
        updateState(connection, profile, artifact, generation, nextSlot, asOf);
    }

    private void requireSeedableRows(Connection connection,
                                     String artifact,
                                     Instant asOf) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact)
                + " WHERE " + quote("_valid_until_epoch_ms") + " > ?"
                + " AND NOT EXISTS (SELECT 1 FROM " + EXCLUDED_LIFECYCLE + " e"
                + " WHERE e.lifecycle_id = " + quote(artifact) + "." + quote("_lifecycle_id") + ")"
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
                if (maximum > MAX_ASSIGNABLE_SLOT) {
                    throw new SQLException("Export-slot space is exhausted for " + profile + "/" + artifact);
                }
                return maximum + 1;
            }
        }
    }

    private void seedFreeRanges(Connection connection,
                                String profile,
                                String artifact,
                                Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO export_slot_free_range(
                    profile, artifact, range_start, range_end, released_at_ms)
                WITH ordered AS (
                    SELECT slot,
                           LAG(slot, 1, 0) OVER (ORDER BY slot) AS previous_slot
                    FROM export_slot_assignment
                    WHERE profile = ? AND artifact = ?
                )
                SELECT ?, ?, previous_slot + 1, slot - 1, ?
                FROM ordered
                WHERE slot - 1 > previous_slot
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setString(3, profile);
            statement.setString(4, artifact);
            statement.setLong(5, asOf.toEpochMilli());
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
        String collect = "INSERT INTO " + RELEASED_SLOT + "(slot, released_at_ms)"
                + " SELECT a.slot, ? FROM export_slot_assignment a"
                + " WHERE a.profile = ? AND a.artifact = ?"
                + " AND NOT EXISTS (" + activeLifecycle + ")";
        int released;
        try (PreparedStatement statement = connection.prepareStatement(collect)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.setLong(4, asOf.toEpochMilli());
            released = statement.executeUpdate();
        }
        if (released == 0) {
            return;
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
        mergeReleasedRanges(connection, profile, artifact);
    }

    private void mergeReleasedRanges(Connection connection,
                                     String profile,
                                     String artifact) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + MERGED_RANGE);
        }
        String merge = """
                INSERT INTO ${merged}(range_start, range_end, released_at_ms)
                WITH intervals AS (
                    SELECT range_start, range_end, released_at_ms
                    FROM export_slot_free_range
                    WHERE profile = ? AND artifact = ?
                    UNION ALL
                    SELECT slot, slot, released_at_ms FROM ${released}
                ), ordered AS (
                    SELECT range_start,
                           range_end,
                           released_at_ms,
                           MAX(range_end) OVER (
                               ORDER BY range_start, range_end
                               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_end
                    FROM intervals
                ), marked AS (
                    SELECT range_start,
                           range_end,
                           released_at_ms,
                           CASE WHEN prior_end IS NULL OR range_start - 1 > prior_end
                                THEN 1 ELSE 0 END AS begins_range
                    FROM ordered
                ), grouped AS (
                    SELECT range_start,
                           range_end,
                           released_at_ms,
                           SUM(begins_range) OVER (
                               ORDER BY range_start, range_end) AS range_group
                    FROM marked
                )
                SELECT MIN(range_start), MAX(range_end), MAX(released_at_ms)
                FROM grouped
                GROUP BY range_group
                """
                .replace("${merged}", MERGED_RANGE)
                .replace("${released}", RELEASED_SLOT);
        try (PreparedStatement statement = connection.prepareStatement(merge)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM export_slot_free_range
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.executeUpdate();
        }
        String publish = "INSERT INTO export_slot_free_range("
                + "profile, artifact, range_start, range_end, released_at_ms)"
                + " SELECT ?, ?, range_start, range_end, released_at_ms FROM " + MERGED_RANGE;
        try (PreparedStatement statement = connection.prepareStatement(publish)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.executeUpdate();
        }
    }

    private void assignRemainingActive(Connection connection,
                                       String profile,
                                       String artifact,
                                       Instant asOf) throws SQLException {
        populatePendingLifecycles(connection, profile, artifact, asOf);
        long pending = scratchCount(connection, PENDING_LIFECYCLE);
        if (pending == 0) {
            return;
        }
        long fromRanges = availableFromRanges(connection, profile, artifact, pending);
        if (fromRanges > 0) {
            assignFromRanges(connection, profile, artifact, fromRanges, asOf);
            consumeLowestFreeSlots(connection, profile, artifact, fromRanges);
        }
        long fromHighWater = pending - fromRanges;
        if (fromHighWater > 0) {
            assignFromHighWater(connection, profile, artifact, fromRanges, fromHighWater, asOf);
        }
    }

    private void populatePendingLifecycles(Connection connection,
                                           String profile,
                                           String artifact,
                                           Instant asOf) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + PENDING_LIFECYCLE);
        }
        String sql = """
                INSERT INTO ${pending}(ordinal, lifecycle_id)
                SELECT ROW_NUMBER() OVER (ORDER BY t.${lifecycleId}), t.${lifecycleId}
                FROM ${artifact} t
                WHERE t.${validUntil} > ?
                  AND NOT EXISTS (
                      SELECT 1 FROM export_slot_assignment a
                      WHERE a.profile = ? AND a.artifact = ?
                        AND a.lifecycle_id = t.${lifecycleId})
                """
                .replace("${pending}", PENDING_LIFECYCLE)
                .replace("${lifecycleId}", quote("_lifecycle_id"))
                .replace("${artifact}", quote(artifact))
                .replace("${validUntil}", quote("_valid_until_epoch_ms"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, asOf.toEpochMilli());
            statement.setString(2, profile);
            statement.setString(3, artifact);
            statement.executeUpdate();
        }
    }

    private long availableFromRanges(Connection connection,
                                     String profile,
                                     String artifact,
                                     long limit) throws SQLException {
        long available = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT range_start, range_end
                FROM export_slot_free_range
                WHERE profile = ? AND artifact = ?
                ORDER BY range_start
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (available < limit && resultSet.next()) {
                    long length = resultSet.getLong("range_end")
                            - resultSet.getLong("range_start") + 1;
                    available += Math.min(length, limit - available);
                }
            }
        }
        return available;
    }

    private void assignFromRanges(Connection connection,
                                  String profile,
                                  String artifact,
                                  long count,
                                  Instant asOf) throws SQLException {
        String sql = """
                INSERT INTO export_slot_assignment(
                    profile, artifact, lifecycle_id, slot, assigned_at_ms)
                WITH ranges AS (
                    SELECT range_start,
                           COALESCE(SUM(range_end - range_start + 1) OVER (
                               ORDER BY range_start
                               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before_count,
                           SUM(range_end - range_start + 1) OVER (
                               ORDER BY range_start) AS through_count
                    FROM export_slot_free_range
                    WHERE profile = ? AND artifact = ?
                )
                SELECT ?, ?, p.lifecycle_id,
                       r.range_start + (p.ordinal - r.before_count) - 1,
                       ?
                FROM ${pending} p
                JOIN ranges r
                  ON p.ordinal > r.before_count
                 AND p.ordinal <= r.through_count
                WHERE p.ordinal <= ?
                ORDER BY p.ordinal
                """.replace("${pending}", PENDING_LIFECYCLE);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setString(3, profile);
            statement.setString(4, artifact);
            statement.setLong(5, asOf.toEpochMilli());
            statement.setLong(6, count);
            if (statement.executeUpdate() != count) {
                throw new SQLException("Export-slot free-range assignment count changed for "
                        + profile + "/" + artifact);
            }
        }
    }

    private void consumeLowestFreeSlots(Connection connection,
                                        String profile,
                                        String artifact,
                                        long count) throws SQLException {
        RangeBoundary boundary = allocationBoundary(connection, profile, artifact, count);
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM export_slot_free_range
                WHERE profile = ? AND artifact = ? AND range_start < ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, boundary.rangeStart());
            statement.executeUpdate();
        }
        if (boundary.assignedThrough() == boundary.rangeEnd()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM export_slot_free_range
                    WHERE profile = ? AND artifact = ? AND range_start = ?
                    """)) {
                statement.setString(1, profile);
                statement.setString(2, artifact);
                statement.setLong(3, boundary.rangeStart());
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE export_slot_free_range
                    SET range_start = ?
                    WHERE profile = ? AND artifact = ? AND range_start = ?
                    """)) {
                statement.setLong(1, boundary.assignedThrough() + 1);
                statement.setString(2, profile);
                statement.setString(3, artifact);
                statement.setLong(4, boundary.rangeStart());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Export-slot free-range boundary disappeared for "
                            + profile + "/" + artifact);
                }
            }
        }
    }

    private RangeBoundary allocationBoundary(Connection connection,
                                              String profile,
                                              String artifact,
                                              long count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH ranges AS (
                    SELECT range_start,
                           range_end,
                           COALESCE(SUM(range_end - range_start + 1) OVER (
                               ORDER BY range_start
                               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before_count,
                           SUM(range_end - range_start + 1) OVER (
                               ORDER BY range_start) AS through_count
                    FROM export_slot_free_range
                    WHERE profile = ? AND artifact = ?
                )
                SELECT range_start, range_end,
                       range_start + (? - before_count) - 1 AS assigned_through
                FROM ranges
                WHERE ? <= through_count
                ORDER BY range_start
                LIMIT 1
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, count);
            statement.setLong(4, count);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Export-slot free-range boundary is missing for "
                            + profile + "/" + artifact);
                }
                return new RangeBoundary(
                        resultSet.getLong("range_start"),
                        resultSet.getLong("range_end"),
                        resultSet.getLong("assigned_through"));
            }
        }
    }

    private void assignFromHighWater(Connection connection,
                                     String profile,
                                     String artifact,
                                     long ordinalOffset,
                                     long count,
                                     Instant asOf) throws SQLException {
        long nextSlot = readNextSlot(connection, profile, artifact);
        if (count > Long.MAX_VALUE - nextSlot) {
            throw new SQLException("Export-slot space is exhausted for " + profile + "/" + artifact);
        }
        String insert = "INSERT INTO export_slot_assignment("
                + "profile, artifact, lifecycle_id, slot, assigned_at_ms)"
                + " SELECT ?, ?, lifecycle_id, ? + (ordinal - ?) - 1, ?"
                + " FROM " + PENDING_LIFECYCLE
                + " WHERE ordinal > ? ORDER BY ordinal";
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, nextSlot);
            statement.setLong(4, ordinalOffset);
            statement.setLong(5, asOf.toEpochMilli());
            statement.setLong(6, ordinalOffset);
            if (statement.executeUpdate() != count) {
                throw new SQLException("Export-slot high-water assignment count changed for "
                        + profile + "/" + artifact);
            }
        }
        setNextSlot(connection, profile, artifact, nextSlot + count, asOf);
    }

    private PreferredExportSlotResolution resolvePreferred(
            Connection connection,
            String profile,
            String artifact,
            PreferredExportSlotRequest request,
            Instant asOf) throws SQLException {
        Long existing = assignedSlot(connection, profile, artifact, request.lifecycleId());
        if (existing != null) {
            PreferredExportSlotResolution.Outcome outcome = existing == request.requestedSlot()
                    ? PreferredExportSlotResolution.Outcome.SURVIVOR_MATCH
                    : PreferredExportSlotResolution.Outcome.SURVIVOR_MISMATCH_PRESERVED;
            return new PreferredExportSlotResolution(
                    request.lifecycleId(), request.requestedSlot(), existing, outcome);
        }
        if (!request.newLifecycle()) {
            throw new SQLException("Matched survivor has no stable export-slot assignment for "
                    + profile + "/" + artifact);
        }
        requireActiveLifecycle(connection, artifact, request.lifecycleId(), asOf);

        boolean occupied = isAssignedSlot(connection, profile, artifact, request.requestedSlot());
        long assigned = occupied
                ? takeSmallestAvailable(connection, profile, artifact, asOf)
                : takeRequested(connection, profile, artifact, request.requestedSlot(), asOf);
        insertAssignment(connection, profile, artifact, request.lifecycleId(), assigned, asOf);
        return new PreferredExportSlotResolution(
                request.lifecycleId(), request.requestedSlot(), assigned,
                occupied
                        ? PreferredExportSlotResolution.Outcome.OCCUPIED_FALLBACK
                        : PreferredExportSlotResolution.Outcome.EXACT);
    }

    private void validatePreferredSurvivors(Connection connection,
                                            String profile,
                                            String artifact,
                                            List<PreferredExportSlotRequest> requests) throws SQLException {
        List<Long> conflicts = new ArrayList<>();
        for (PreferredExportSlotRequest request : requests) {
            Long existing = assignedSlot(connection, profile, artifact, request.lifecycleId());
            if (existing != null
                    && existing != request.requestedSlot()
                    && request.existingRecordPolicy() == ImportExistingSlotPolicy.REJECT_MISMATCH) {
                conflicts.add(request.lifecycleId());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new PreferredExportSlotConflictException(
                    PreferredExportSlotConflictException.Reason.SURVIVOR_MISMATCH,
                    conflicts,
                    "Preferred export slot differs from a stable survivor assignment");
        }
    }

    private void validatePreferredRequests(List<PreferredExportSlotRequest> requests) throws SQLException {
        Map<Long, Long> lifecycleRequests = new HashMap<>();
        Map<Long, List<Long>> slotLifecycles = new LinkedHashMap<>();
        for (PreferredExportSlotRequest request : requests) {
            Long previous = lifecycleRequests.putIfAbsent(request.lifecycleId(), request.requestedSlot());
            if (previous != null) {
                throw new PreferredExportSlotConflictException(
                        PreferredExportSlotConflictException.Reason.DUPLICATE_REQUEST,
                        List.of(request.lifecycleId()),
                        "One lifecycle has multiple preferred export-slot requests");
            }
            slotLifecycles.computeIfAbsent(request.requestedSlot(), ignored -> new ArrayList<>())
                    .add(request.lifecycleId());
        }
        List<Long> conflicts = slotLifecycles.values().stream()
                .filter(lifecycles -> lifecycles.size() > 1)
                .flatMap(List::stream)
                .toList();
        if (!conflicts.isEmpty()) {
            throw new PreferredExportSlotConflictException(
                    PreferredExportSlotConflictException.Reason.DUPLICATE_REQUEST,
                    conflicts,
                    "Multiple lifecycles request the same preferred export slot");
        }
    }

    private void excludeNewLifecycles(Connection connection,
                                      List<PreferredExportSlotRequest> requests) throws SQLException {
        String sql = "INSERT INTO " + EXCLUDED_LIFECYCLE + "(lifecycle_id) VALUES (?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PreferredExportSlotRequest request : requests) {
                if (request.newLifecycle()) {
                    statement.setLong(1, request.lifecycleId());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private long takeRequested(Connection connection,
                               String profile,
                               String artifact,
                               long requested,
                               Instant asOf) throws SQLException {
        long nextSlot = readNextSlot(connection, profile, artifact);
        if (requested < nextSlot) {
            if (!removeFromFreeRange(connection, profile, artifact, requested)) {
                throw new SQLException("Untracked free export slot for " + profile + "/" + artifact);
            }
            return requested;
        }
        if (requested > nextSlot) {
            addFreeRange(connection, profile, artifact, nextSlot, requested - 1, asOf.toEpochMilli());
        }
        setNextSlot(connection, profile, artifact, requested + 1, asOf);
        return requested;
    }

    private long takeSmallestAvailable(Connection connection,
                                       String profile,
                                       String artifact,
                                       Instant asOf) throws SQLException {
        Range smallest = firstRange(connection, profile, artifact);
        if (smallest != null) {
            long slot = smallest.start();
            removeFromFreeRange(connection, profile, artifact, slot);
            return slot;
        }
        long nextSlot = readNextSlot(connection, profile, artifact);
        if (nextSlot > MAX_ASSIGNABLE_SLOT) {
            throw new SQLException("Export-slot space is exhausted for " + profile + "/" + artifact);
        }
        setNextSlot(connection, profile, artifact, nextSlot + 1, asOf);
        return nextSlot;
    }

    private boolean removeFromFreeRange(Connection connection,
                                        String profile,
                                        String artifact,
                                        long slot) throws SQLException {
        Range containing = containingRange(connection, profile, artifact, slot);
        if (containing == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM export_slot_free_range
                WHERE profile = ? AND artifact = ? AND range_start = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, containing.start());
            statement.executeUpdate();
        }
        if (containing.start() < slot) {
            insertFreeRange(connection, profile, artifact,
                    containing.start(), slot - 1, containing.releasedAtMs());
        }
        if (slot < containing.end()) {
            insertFreeRange(connection, profile, artifact,
                    slot + 1, containing.end(), containing.releasedAtMs());
        }
        return true;
    }

    private void addFreeRange(Connection connection,
                              String profile,
                              String artifact,
                              long start,
                              long end,
                              long releasedAtMs) throws SQLException {
        if (start > end) {
            return;
        }
        List<Range> adjacent = new ArrayList<>(2);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT range_start, range_end, released_at_ms
                FROM export_slot_free_range
                WHERE profile = ? AND artifact = ?
                  AND range_start <= ?
                  AND range_end >= ?
                ORDER BY range_start
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, end + 1);
            statement.setLong(4, start - 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    adjacent.add(range(resultSet));
                }
            }
        }
        long mergedStart = start;
        long mergedEnd = end;
        long mergedReleasedAt = releasedAtMs;
        for (Range range : adjacent) {
            mergedStart = Math.min(mergedStart, range.start());
            mergedEnd = Math.max(mergedEnd, range.end());
            mergedReleasedAt = Math.max(mergedReleasedAt, range.releasedAtMs());
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM export_slot_free_range
                    WHERE profile = ? AND artifact = ? AND range_start = ?
                    """)) {
                statement.setString(1, profile);
                statement.setString(2, artifact);
                statement.setLong(3, range.start());
                statement.executeUpdate();
            }
        }
        insertFreeRange(connection, profile, artifact,
                mergedStart, mergedEnd, mergedReleasedAt);
    }

    private void insertFreeRange(Connection connection,
                                 String profile,
                                 String artifact,
                                 long start,
                                 long end,
                                 long releasedAtMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO export_slot_free_range(
                    profile, artifact, range_start, range_end, released_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, start);
            statement.setLong(4, end);
            statement.setLong(5, releasedAtMs);
            statement.executeUpdate();
        }
    }

    private Range containingRange(Connection connection,
                                  String profile,
                                  String artifact,
                                  long slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT range_start, range_end, released_at_ms
                FROM export_slot_free_range
                WHERE profile = ? AND artifact = ?
                  AND range_start <= ? AND range_end >= ?
                ORDER BY range_start DESC
                LIMIT 1
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, slot);
            statement.setLong(4, slot);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? range(resultSet) : null;
            }
        }
    }

    private Range firstRange(Connection connection,
                             String profile,
                             String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT range_start, range_end, released_at_ms
                FROM export_slot_free_range
                WHERE profile = ? AND artifact = ?
                ORDER BY range_start
                LIMIT 1
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? range(resultSet) : null;
            }
        }
    }

    private void insertAssignment(Connection connection,
                                  String profile,
                                  String artifact,
                                  long lifecycleId,
                                  long slot,
                                  Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO export_slot_assignment(
                    profile, artifact, lifecycle_id, slot, assigned_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, lifecycleId);
            statement.setLong(4, slot);
            statement.setLong(5, asOf.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private Long assignedSlot(Connection connection,
                              String profile,
                              String artifact,
                              long lifecycleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT slot FROM export_slot_assignment
                WHERE profile = ? AND artifact = ? AND lifecycle_id = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, lifecycleId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : null;
            }
        }
    }

    private boolean isAssignedSlot(Connection connection,
                                   String profile,
                                   String artifact,
                                   long slot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM export_slot_assignment
                WHERE profile = ? AND artifact = ? AND slot = ?
                LIMIT 1
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            statement.setLong(3, slot);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void requireActiveLifecycle(Connection connection,
                                        String artifact,
                                        long lifecycleId,
                                        Instant asOf) throws SQLException {
        String sql = "SELECT 1 FROM " + quote(artifact)
                + " WHERE " + quote("_lifecycle_id") + " = ?"
                + " AND " + quote("_valid_until_epoch_ms") + " > ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lifecycleId);
            statement.setLong(2, asOf.toEpochMilli());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Preferred export slot requires an active lifecycle in " + artifact);
                }
            }
        }
    }

    private long readNextSlot(Connection connection,
                              String profile,
                              String artifact) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT next_slot FROM export_slot_state
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setString(1, profile);
            statement.setString(2, artifact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Export-slot state disappeared for " + profile + "/" + artifact);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private void setNextSlot(Connection connection,
                             String profile,
                             String artifact,
                             long nextSlot,
                             Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE export_slot_state
                SET next_slot = ?, updated_at_ms = ?
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setLong(1, nextSlot);
            statement.setLong(2, asOf.toEpochMilli());
            statement.setString(3, profile);
            statement.setString(4, artifact);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Export-slot state disappeared for " + profile + "/" + artifact);
            }
        }
    }

    private void updateGeneration(Connection connection,
                                  String profile,
                                  String artifact,
                                  long generation,
                                  Instant asOf) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE export_slot_state
                SET source_generation = ?, updated_at_ms = ?
                WHERE profile = ? AND artifact = ?
                """)) {
            statement.setLong(1, generation);
            statement.setLong(2, asOf.toEpochMilli());
            statement.setString(3, profile);
            statement.setString(4, artifact);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Export-slot state disappeared for " + profile + "/" + artifact);
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

    private void ensureScratchTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS " + EXCLUDED_LIFECYCLE
                    + "(lifecycle_id INTEGER PRIMARY KEY) WITHOUT ROWID");
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS " + RELEASED_SLOT
                    + "(slot INTEGER PRIMARY KEY, released_at_ms INTEGER NOT NULL) WITHOUT ROWID");
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS " + MERGED_RANGE
                    + "(range_start INTEGER PRIMARY KEY, range_end INTEGER NOT NULL,"
                    + " released_at_ms INTEGER NOT NULL) WITHOUT ROWID");
            statement.execute("CREATE TEMP TABLE IF NOT EXISTS " + PENDING_LIFECYCLE
                    + "(ordinal INTEGER PRIMARY KEY, lifecycle_id INTEGER NOT NULL UNIQUE)");
        }
    }

    private void clearScratchTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + EXCLUDED_LIFECYCLE);
            statement.executeUpdate("DELETE FROM " + RELEASED_SLOT);
            statement.executeUpdate("DELETE FROM " + MERGED_RANGE);
            statement.executeUpdate("DELETE FROM " + PENDING_LIFECYCLE);
        }
    }

    private long scratchCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (!resultSet.next()) {
                throw new SQLException("Export-slot scratch count returned no row");
            }
            return resultSet.getLong(1);
        }
    }

    private static Range range(ResultSet resultSet) throws SQLException {
        return new Range(
                resultSet.getLong("range_start"),
                resultSet.getLong("range_end"),
                resultSet.getLong("released_at_ms"));
    }

    private static String quote(String identifier) {
        // SQLite cannot bind identifiers; validate the complete identifier before quoting it.
        return "\"" + DataframeColumn.requireSqlIdentifier(identifier, "identifier") + "\"";
    }

    private record Range(long start, long end, long releasedAtMs) {
    }

    private record RangeBoundary(long rangeStart, long rangeEnd, long assignedThrough) {
    }

    /** Signals a benign generation race that the snapshot reader may retry. */
    static final class SnapshotChangedException extends SQLException {

        private static final long serialVersionUID = 1L;

        SnapshotChangedException(String message) {
            super(message);
        }
    }
}
