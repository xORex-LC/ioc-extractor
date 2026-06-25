package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.artifact.IngestRunStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRunLedgerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;

    @AfterEach
    void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void tracks_incomplete_ingest_run_until_completion() {
        var ledger = ledger();

        var run = ledger.startIngest("source-1", List.of("masks", "hashes"));
        ledger.markDbCommitted(run.runId());

        assertThat(ledger.findIncompleteIngestRuns())
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.runId()).isEqualTo(run.runId());
                    assertThat(saved.sourceKey()).isEqualTo("source-1");
                    assertThat(saved.status()).isEqualTo(IngestRunStatus.DB_COMMITTED);
                    assertThat(saved.artifacts()).containsExactly("masks", "hashes");
                });

        ledger.markProjectionCompleted(run.runId());
        ledger.markCompleted(run.runId());

        assertThat(ledger.findIncompleteIngestRuns()).isEmpty();
    }

    private JdbcRunLedger ledger() {
        dataSource = dataSource("run-ledger.db");
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        return new JdbcRunLedger(dataSource, CLOCK);
    }

    private HikariDataSource dataSource(String fileName) {
        Path db = tempDir.resolve(fileName);
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings("service", "jdbc:sqlite:" + db, "low-memory", 1, 1));
    }
}
