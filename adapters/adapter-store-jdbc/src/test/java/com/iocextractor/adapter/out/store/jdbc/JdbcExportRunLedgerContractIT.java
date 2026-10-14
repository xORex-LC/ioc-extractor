package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.ContractTest;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.tck.export.ExportRunLedgerContractTest;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@ContractTest
class JdbcExportRunLedgerContractIT extends ExportRunLedgerContractTest {

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void closeDataSources() {
        dataSources.forEach(dataSource -> dataSource.close());
    }

    @Override
    protected LedgerFixture createFixture() {
        HikariDataSource dataSource = dataSource("export-contract-" + System.nanoTime() + ".db");
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        return new LedgerFixture(
                new JdbcExportRunLedger(dataSource, CLOCK),
                new JdbcExportProgressStore(dataSource));
    }

    @Test
    void preservesMonotonicRunTimeWhenTheWallClockMovesBackwards() {
        HikariDataSource dataSource = dataSource("export-backward-clock.db");
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
        var ledger = new JdbcExportRunLedger(
                dataSource, Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC));
        ExportRun started = ExportRun.started(
                "run-backward-clock", "reputation", "slice-backward-clock", PLAN_HASH, NOW);

        ledger.tryStart(started);
        ExportRun staged = ledger.transition(started.runId(), ExportRunStatus.STARTED,
                ExportRunStatus.STAGED, MANIFEST_HASH, null);

        assertThat(staged.updatedAt()).isEqualTo(NOW);
    }

    private HikariDataSource dataSource(String fileName) {
        HikariDataSource dataSource = new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 2));
        dataSources.add(dataSource);
        return dataSource;
    }
}
