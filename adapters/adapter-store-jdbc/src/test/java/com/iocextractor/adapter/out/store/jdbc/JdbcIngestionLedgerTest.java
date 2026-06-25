package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.port.out.ingest.IngestionLedger;
import com.iocextractor.application.tck.ingest.IngestionLedgerContractTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
