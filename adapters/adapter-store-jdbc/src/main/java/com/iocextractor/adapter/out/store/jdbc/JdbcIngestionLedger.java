package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.ingest.IngestionLedgerTransition;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC-backed implementation of the durable ingestion ledger.
 */
public final class JdbcIngestionLedger implements IngestionLedger {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcIngestionLedger(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    JdbcIngestionLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<IngestionRecord> find(ObservationId observationId) {
        return jdbc.sql("""
                        SELECT observation_id, source_key, status, original_path, processing_path, archived_path,
                               detected_at, updated_at, reason
                        FROM ingestion_ledger
                        WHERE observation_id = :observation_id
                        """)
                .param("observation_id", observationId.value())
                .query((rs, rowNum) -> row(rs))
                .optional()
                .map(this::record);
    }

    @Override
    public IngestionLedgerTransition markClaimed(SourceUnit unit) {
        Instant now = Instant.now(clock);
        int changed = jdbc.sql("""
                        INSERT INTO ingestion_ledger (
                            observation_id, source_key, status, original_path, processing_path,
                            archived_path, detected_at, updated_at, reason
                        ) VALUES (
                            :observation_id, :source_key, :status, :original_path, :processing_path,
                            NULL, :detected_at, :updated_at, NULL
                        )
                        ON CONFLICT(observation_id) DO NOTHING
                        """)
                .param("observation_id", unit.observationId().value())
                .param("source_key", unit.key().value())
                .param("status", IngestionStatus.CLAIMED.name())
                .param("original_path", unit.originalPath().toString())
                .param("processing_path", unit.processingPath().toString())
                .param("detected_at", unit.detectedAt().toString())
                .param("updated_at", now.toString())
                .update();
        return changed == 1
                ? IngestionLedgerTransition.APPLIED
                : resolve(unit.observationId(), IngestionStatus.CLAIMED);
    }

    @Override
    public IngestionLedgerTransition markSourceArchived(ObservationId observationId, Path archivedPath) {
        int changed = jdbc.sql("""
                        UPDATE ingestion_ledger
                        SET status = :status,
                            archived_path = :archived_path,
                            updated_at = :updated_at
                        WHERE observation_id = :observation_id
                          AND status = :expected_status
                        """)
                .param("status", IngestionStatus.SOURCE_ARCHIVED.name())
                .param("archived_path", archivedPath.toString())
                .param("updated_at", Instant.now(clock).toString())
                .param("observation_id", observationId.value())
                .param("expected_status", IngestionStatus.CLAIMED.name())
                .update();
        return changed == 1
                ? IngestionLedgerTransition.APPLIED
                : resolve(observationId, IngestionStatus.SOURCE_ARCHIVED);
    }

    @Override
    public IngestionLedgerTransition markFailed(ObservationId observationId,
                                                SourceKey key,
                                                String reason) {
        Instant now = Instant.now(clock);
        int changed = jdbc.sql("""
                        INSERT INTO ingestion_ledger (
                            observation_id, source_key, status, original_path, processing_path,
                            archived_path, detected_at, updated_at, reason
                        ) VALUES (
                            :observation_id, :source_key, :status, :original_path, :processing_path,
                            NULL, :detected_at, :updated_at, :reason
                        )
                        ON CONFLICT(observation_id) DO UPDATE SET
                            status = excluded.status,
                            updated_at = excluded.updated_at,
                            reason = excluded.reason
                        WHERE ingestion_ledger.status = :expected_status
                        """)
                .param("observation_id", observationId.value())
                .param("source_key", key.value())
                .param("status", IngestionStatus.FAILED.name())
                .param("original_path", "unknown")
                .param("processing_path", "unknown")
                .param("detected_at", now.toString())
                .param("updated_at", now.toString())
                .param("reason", reason)
                .param("expected_status", IngestionStatus.CLAIMED.name())
                .update();
        return changed == 1
                ? IngestionLedgerTransition.APPLIED
                : resolve(observationId, IngestionStatus.FAILED);
    }

    @Override
    public List<IngestionRecord> findIncomplete() {
        return jdbc.sql("""
                        SELECT observation_id, source_key, status, original_path, processing_path, archived_path,
                               detected_at, updated_at, reason
                        FROM ingestion_ledger
                        WHERE status NOT IN ('SOURCE_ARCHIVED', 'FAILED')
                        ORDER BY detected_at, observation_id
                        """)
                .query((rs, rowNum) -> row(rs))
                .list()
                .stream()
                .map(this::record)
                .toList();
    }

    private IngestionLedgerTransition resolve(ObservationId observationId, IngestionStatus target) {
        Optional<IngestionRecord> current = find(observationId);
        if (current.isEmpty()) {
            return IngestionLedgerTransition.MISSING;
        }
        return current.orElseThrow().status() == target
                ? IngestionLedgerTransition.ALREADY_APPLIED
                : IngestionLedgerTransition.CONFLICT;
    }

    private LedgerRow row(ResultSet rs) throws SQLException {
        return new LedgerRow(
                new ObservationId(rs.getString("observation_id")),
                new SourceKey(rs.getString("source_key")),
                status(rs.getString("status")),
                Path.of(rs.getString("original_path")),
                Path.of(rs.getString("processing_path")),
                optionalPath(rs.getString("archived_path")),
                Instant.parse(rs.getString("detected_at")),
                Instant.parse(rs.getString("updated_at")),
                blankToNull(rs.getString("reason")));
    }

    private IngestionRecord record(LedgerRow row) {
        return new IngestionRecord(
                row.observationId(),
                row.key(),
                row.status(),
                row.originalPath(),
                row.processingPath(),
                row.archivedPath(),
                row.detectedAt(),
                row.updatedAt(),
                row.reason());
    }

    private IngestionStatus status(String value) {
        return IngestionStatus.valueOf(value);
    }

    private Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record LedgerRow(ObservationId observationId,
                             SourceKey key,
                             IngestionStatus status,
                             Path originalPath,
                             Path processingPath,
                             Path archivedPath,
                             Instant detectedAt,
                             Instant updatedAt,
                             String reason) {
    }
}
