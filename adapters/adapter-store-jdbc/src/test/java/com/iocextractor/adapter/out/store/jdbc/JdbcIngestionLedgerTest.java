package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.tck.ingest.IngestionLedgerContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

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
    void old_completed_statuses_are_read_as_archived_for_upgrade_compatibility() throws Exception {
        JdbcIngestionLedger ledger = ledger();
        insertRaw("aggregated", "AGGREGATED");

        assertThat(ledger.find(key("aggregated"))).get()
                .extracting("status")
                .isEqualTo(com.iocextractor.application.ingest.IngestionStatus.SOURCE_ARCHIVED);
    }

    @Test
    void old_intermediate_statuses_are_read_as_claimed_for_upgrade_compatibility() throws Exception {
        JdbcIngestionLedger ledger = ledger();
        insertRaw("partition-written", "PARTITION_WRITTEN");

        assertThat(ledger.find(key("partition-written"))).get()
                .extracting("status")
                .isEqualTo(com.iocextractor.application.ingest.IngestionStatus.CLAIMED);
    }

    @Test
    void incomplete_records_are_returned_by_detected_time_then_source_key() {
        JdbcIngestionLedger ledger = ledger();
        SourceUnit second = unit("b-second");
        SourceUnit firstB = new SourceUnit(key("b-first"), path("inbox/b-first.html"),
                path("processing/b-first.html"), DETECTED_AT.minusSeconds(60));
        SourceUnit firstA = new SourceUnit(key("a-first"), path("inbox/a-first.html"),
                path("processing/a-first.html"), DETECTED_AT.minusSeconds(60));

        ledger.markClaimed(second);
        ledger.markClaimed(firstB);
        ledger.markClaimed(firstA);

        assertThat(ledger.findIncomplete())
                .extracting(record -> record.key().value())
                .containsExactly("a-first", "b-first", "b-second");
    }

    private JdbcIngestionLedger ledger() {
        return (JdbcIngestionLedger) createLedger(FIXED_CLOCK);
    }

    private void insertRaw(String sourceKey, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO ingestion_ledger (
                         source_key, status, original_path, processing_path, detected_at, updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, sourceKey);
            statement.setString(2, status);
            statement.setString(3, "inbox/" + sourceKey + ".html");
            statement.setString(4, "processing/" + sourceKey + ".html");
            statement.setString(5, DETECTED_AT.toString());
            statement.setString(6, DETECTED_AT.toString());
            statement.executeUpdate();
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
