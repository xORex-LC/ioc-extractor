package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.IngestionRecord;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.common.IocExtractorException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcIngestionLedger(DataSource dataSource, Clock clock) {
        this(JdbcClient.create(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                clock);
    }

    JdbcIngestionLedger(JdbcClient jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<IngestionRecord> find(SourceKey key) {
        return jdbc.sql("""
                        SELECT source_key, status, original_path, processing_path, archived_path,
                               detected_at, updated_at, reason
                        FROM ingestion_ledger
                        WHERE source_key = :source_key
                        """)
                .param("source_key", key.value())
                .query((rs, rowNum) -> row(rs))
                .optional()
                .map(this::record);
    }

    @Override
    public void markClaimed(SourceUnit unit) {
        inTransaction(() -> {
            Instant now = Instant.now(clock);
            jdbc.sql("""
                            INSERT INTO ingestion_ledger (
                                source_key, status, original_path, processing_path,
                                archived_path, detected_at, updated_at, reason
                            ) VALUES (
                                :source_key, :status, :original_path, :processing_path,
                                NULL, :detected_at, :updated_at, NULL
                            )
                            ON CONFLICT(source_key) DO UPDATE SET
                                status = excluded.status,
                                original_path = excluded.original_path,
                                processing_path = excluded.processing_path,
                                archived_path = NULL,
                                detected_at = excluded.detected_at,
                                updated_at = excluded.updated_at,
                                reason = NULL
                            """)
                    .param("source_key", unit.key().value())
                    .param("status", IngestionStatus.CLAIMED.name())
                    .param("original_path", unit.originalPath().toString())
                    .param("processing_path", unit.processingPath().toString())
                    .param("detected_at", unit.detectedAt().toString())
                    .param("updated_at", now.toString())
                    .update();
            replacePartitions(unit.key(), List.of());
        });
    }

    @Override
    public void markPartitionWritten(SourceKey key, List<Path> partitions) {
        inTransaction(() -> {
            IngestionRecord record = require(key);
            update(record, IngestionStatus.PARTITION_WRITTEN, record.archivedPath(), record.reason());
            replacePartitions(key, partitions);
        });
    }

    @Override
    public void markLedgerRecorded(SourceKey key) {
        IngestionRecord record = require(key);
        update(record, IngestionStatus.LEDGER_RECORDED, record.archivedPath(), record.reason());
    }

    @Override
    public void markSourceArchived(SourceKey key, Path archivedPath) {
        IngestionRecord record = require(key);
        update(record, IngestionStatus.SOURCE_ARCHIVED, archivedPath, record.reason());
    }

    @Override
    public void markAggregated(SourceKey key) {
        IngestionRecord record = require(key);
        update(record, IngestionStatus.AGGREGATED, record.archivedPath(), record.reason());
    }

    @Override
    public void markFailed(SourceKey key, String reason) {
        Optional<IngestionRecord> existing = find(key);
        IngestionRecord record = existing.orElse(new IngestionRecord(key, IngestionStatus.FAILED,
                Path.of("unknown"), Path.of("unknown"), null, List.of(),
                Instant.now(clock), Instant.now(clock), reason));
        if (existing.isEmpty()) {
            insertFailed(record, reason);
            return;
        }
        update(record, IngestionStatus.FAILED, record.archivedPath(), reason);
    }

    @Override
    public List<IngestionRecord> findIncomplete() {
        return jdbc.sql("""
                        SELECT source_key, status, original_path, processing_path, archived_path,
                               detected_at, updated_at, reason
                        FROM ingestion_ledger
                        WHERE status NOT IN ('SOURCE_ARCHIVED', 'AGGREGATED', 'FAILED')
                        ORDER BY detected_at, source_key
                        """)
                .query((rs, rowNum) -> row(rs))
                .list()
                .stream()
                .map(this::record)
                .toList();
    }

    @Override
    public List<IngestionRecord> findReadyForAggregation() {
        return jdbc.sql("""
                        SELECT l.source_key, l.status, l.original_path, l.processing_path, l.archived_path,
                               l.detected_at, l.updated_at, l.reason
                        FROM ingestion_ledger l
                        WHERE l.status = 'SOURCE_ARCHIVED'
                          AND EXISTS (
                              SELECT 1 FROM ingestion_partition p WHERE p.source_key = l.source_key
                          )
                        ORDER BY l.detected_at, l.source_key
                        """)
                .query((rs, rowNum) -> row(rs))
                .list()
                .stream()
                .map(this::record)
                .toList();
    }

    @Override
    public List<IngestionRecord> findAggregated() {
        return jdbc.sql("""
                        SELECT l.source_key, l.status, l.original_path, l.processing_path, l.archived_path,
                               l.detected_at, l.updated_at, l.reason
                        FROM ingestion_ledger l
                        WHERE l.status = 'AGGREGATED'
                          AND EXISTS (
                              SELECT 1 FROM ingestion_partition p WHERE p.source_key = l.source_key
                          )
                        ORDER BY l.detected_at, l.source_key
                        """)
                .query((rs, rowNum) -> row(rs))
                .list()
                .stream()
                .map(this::record)
                .toList();
    }

    private IngestionRecord require(SourceKey key) {
        return find(key).orElseThrow(() -> new IocExtractorException("Missing ingestion ledger record: " + key.value()));
    }

    private void update(IngestionRecord record,
                        IngestionStatus status,
                        Path archivedPath,
                        String reason) {
        jdbc.sql("""
                        UPDATE ingestion_ledger
                        SET status = :status,
                            archived_path = :archived_path,
                            updated_at = :updated_at,
                            reason = :reason
                        WHERE source_key = :source_key
                        """)
                .param("status", status.name())
                .param("archived_path", archivedPath == null ? null : archivedPath.toString())
                .param("updated_at", Instant.now(clock).toString())
                .param("reason", reason)
                .param("source_key", record.key().value())
                .update();
    }

    private void insertFailed(IngestionRecord record, String reason) {
        jdbc.sql("""
                        INSERT INTO ingestion_ledger (
                            source_key, status, original_path, processing_path,
                            archived_path, detected_at, updated_at, reason
                        ) VALUES (
                            :source_key, :status, :original_path, :processing_path,
                            NULL, :detected_at, :updated_at, :reason
                        )
                        """)
                .param("source_key", record.key().value())
                .param("status", IngestionStatus.FAILED.name())
                .param("original_path", record.originalPath().toString())
                .param("processing_path", record.processingPath().toString())
                .param("detected_at", record.detectedAt().toString())
                .param("updated_at", Instant.now(clock).toString())
                .param("reason", reason)
                .update();
    }

    private void replacePartitions(SourceKey key, List<Path> partitions) {
        jdbc.sql("DELETE FROM ingestion_partition WHERE source_key = :source_key")
                .param("source_key", key.value())
                .update();
        for (Path partition : partitions == null ? List.<Path>of() : partitions) {
            jdbc.sql("""
                            INSERT INTO ingestion_partition (source_key, partition_path)
                            VALUES (:source_key, :partition_path)
                            """)
                    .param("source_key", key.value())
                    .param("partition_path", partition.toString())
                    .update();
        }
    }

    // Only multi-statement mutations (markClaimed, markPartitionWritten) need an
    // explicit transaction to stay atomic. Single-statement UPDATE marks are atomic
    // under autocommit, and the find-then-write marks (markFailed, the status marks
    // via require()) are safe ONLY because of the single-writer model (no concurrent
    // writer can interleave between the read and the write). Revisit this — wrap the
    // read-modify-write marks in inTransaction — if/when concurrent writers are introduced.
    private void inTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }

    private LedgerRow row(ResultSet rs) throws SQLException {
        return new LedgerRow(
                new SourceKey(rs.getString("source_key")),
                IngestionStatus.valueOf(rs.getString("status")),
                Path.of(rs.getString("original_path")),
                Path.of(rs.getString("processing_path")),
                optionalPath(rs.getString("archived_path")),
                Instant.parse(rs.getString("detected_at")),
                Instant.parse(rs.getString("updated_at")),
                blankToNull(rs.getString("reason")));
    }

    private IngestionRecord record(LedgerRow row) {
        return new IngestionRecord(
                row.key(),
                row.status(),
                row.originalPath(),
                row.processingPath(),
                row.archivedPath(),
                partitions(row.key()),
                row.detectedAt(),
                row.updatedAt(),
                row.reason());
    }

    // Partition order is NOT part of the ledger contract: partitions of a source
    // are disjoint per-artifact files and nothing downstream depends on their order.
    // ORDER BY partition_path only makes the result deterministic; it intentionally
    // differs from FileIngestionLedger's insertion order. (Partitions are transitional
    // and removed at the optional β-collapse, O1.)
    private List<Path> partitions(SourceKey key) {
        return jdbc.sql("""
                        SELECT partition_path
                        FROM ingestion_partition
                        WHERE source_key = :source_key
                        ORDER BY partition_path
                        """)
                .param("source_key", key.value())
                .query(String.class)
                .list()
                .stream()
                .map(Path::of)
                .toList();
    }

    private Path optionalPath(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : Path.of(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record LedgerRow(SourceKey key,
                             IngestionStatus status,
                             Path originalPath,
                             Path processingPath,
                             Path archivedPath,
                             Instant detectedAt,
                             Instant updatedAt,
                             String reason) {
    }
}
