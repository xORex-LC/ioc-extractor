package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.port.out.ingest.IngestionLedgerContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIngestionLedgerTest extends IngestionLedgerContractTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        closeDataSource();
    }

    @Override
    protected IngestionLedger createLedger(Clock clock) {
        closeDataSource();
        dataSource = dataSource("ledger-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        return new JdbcIngestionLedger(dataSource, clock);
    }

    @Test
    void replace_partitions_removes_stale_child_rows() throws Exception {
        JdbcIngestionLedger ledger = ledger();
        SourceUnit unit = unit("replace-sql");

        ledger.markClaimed(unit);
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/stale.csv")));
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/current.csv")));

        assertThat(partitionRows(unit.key().value())).containsExactly("partitions/current.csv");
    }

    @Test
    void deleting_ledger_row_cascades_partition_rows() throws Exception {
        JdbcIngestionLedger ledger = ledger();
        SourceUnit unit = unit("cascade");

        ledger.markClaimed(unit);
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/cascade.csv")));

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("DELETE FROM ingestion_ledger WHERE source_key = ?")) {
            statement.setString(1, unit.key().value());
            statement.executeUpdate();
        }

        assertThat(partitionRows(unit.key().value())).isEmpty();
    }

    @Test
    void database_rejects_orphan_partition_rows() throws Exception {
        ledger();

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO ingestion_partition (source_key, partition_path)
                     VALUES (?, ?)
                     """)) {
            statement.setString(1, "missing-parent");
            statement.setString(2, "partitions/orphan.csv");

            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void ready_records_are_returned_by_detected_time_then_source_key() {
        JdbcIngestionLedger ledger = ledger();
        SourceUnit second = unit("b-second");
        SourceUnit firstB = new SourceUnit(key("b-first"), path("inbox/b-first.html"),
                path("processing/b-first.html"), DETECTED_AT.minusSeconds(60));
        SourceUnit firstA = new SourceUnit(key("a-first"), path("inbox/a-first.html"),
                path("processing/a-first.html"), DETECTED_AT.minusSeconds(60));

        markReady(ledger, second);
        markReady(ledger, firstB);
        markReady(ledger, firstA);

        assertThat(ledger.findReadyForAggregation())
                .extracting(record -> record.key().value())
                .containsExactly("a-first", "b-first", "b-second");
    }

    private JdbcIngestionLedger ledger() {
        return (JdbcIngestionLedger) createLedger(FIXED_CLOCK);
    }

    private void markReady(JdbcIngestionLedger ledger, SourceUnit unit) {
        ledger.markClaimed(unit);
        ledger.markPartitionWritten(unit.key(), List.of(path("partitions/" + unit.key().value() + ".csv")));
        ledger.markLedgerRecorded(unit.key());
        ledger.markSourceArchived(unit.key(), path("done/" + unit.key().value() + ".html"));
    }

    private List<String> partitionRows(String sourceKey) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT partition_path
                     FROM ingestion_partition
                     WHERE source_key = ?
                     ORDER BY partition_path
                     """)) {
            statement.setString(1, sourceKey);
            try (var resultSet = statement.executeQuery()) {
                var rows = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    rows.add(resultSet.getString(1));
                }
                return rows;
            }
        }
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }

    private void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
