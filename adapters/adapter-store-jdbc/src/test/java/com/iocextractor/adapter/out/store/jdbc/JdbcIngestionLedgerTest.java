package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
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
import java.time.Instant;

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

    @Test
    void v8_preserves_legacy_attempt_and_allows_a_new_observation_of_the_same_content()
            throws Exception {
        dataSource = dataSource("ledger-v8.db");
        var migrations = ServiceSchemaMigrations.sqlite();
        new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 7)).migrate();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ingestion_ledger(
                        source_key, status, original_path, processing_path,
                        archived_path, detected_at, updated_at, reason)
                    VALUES ('same-content', 'SOURCE_ARCHIVED', 'inbox/old.html',
                            'processing/old.html', 'done/old.html',
                            '2026-08-15T00:00:00Z', '2026-08-15T00:01:00Z', NULL)
                    """);
        }

        var result = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();
        var ledger = new JdbcIngestionLedger(dataSource, FIXED_CLOCK);
        SourceKey content = new SourceKey("same-content");
        ObservationId current = new ObservationId("observation-current");

        assertThat(result.previousVersion()).isEqualTo(7);
        assertThat(result.appliedVersions()).containsExactly(8);
        assertThat(ledger.find(ObservationId.legacy(content.value()))).isPresent();
        assertThat(ledger.markClaimed(new SourceUnit(
                current,
                content,
                path("inbox/current.html"),
                path("processing/current.html"),
                Instant.parse("2026-08-16T00:00:00Z")))).isEqualTo(
                com.iocextractor.application.ingest.IngestionLedgerTransition.APPLIED);
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM ingestion_ledger WHERE source_key = 'same-content'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isEqualTo(2);
        }
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
