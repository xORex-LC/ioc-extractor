package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.export.ExportRun;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.diagnostics.DiagnosticException;
import com.iocextractor.diagnostics.DiagnosticFactory;
import com.iocextractor.diagnostics.codes.ExportDiagnosticCodes;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JdbcExportRunLedgerIT {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PLAN_HASH = "a".repeat(64);
    private static final long WAIT_TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    @Test
    void partial_unique_index_allows_only_one_winner_in_concurrent_start() throws Exception {
        try (HikariDataSource dataSource = dataSource("concurrent-start.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<ExportRun>> first = executor.submit(() -> attemptStart(
                        dataSource, started("run-concurrent-1"), ready, start));
                Future<Optional<ExportRun>> second = executor.submit(() -> attemptStart(
                        dataSource, started("run-concurrent-2"), ready, start));
                try {
                    assertThat(ready.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .as("both export start contenders should become ready")
                            .isTrue();
                } finally {
                    start.countDown();
                }

                assertThat(List.of(
                        first.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        second.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                        .filteredOn(optional -> optional.isPresent())
                        .hasSize(1);
                assertThat(new JdbcExportRunLedger(dataSource, CLOCK).findIncomplete()).hasSize(1);
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("concurrent export start workers should terminate")
                        .isTrue();
            }
        }
    }

    @Test
    void active_run_is_visible_to_a_reopened_ledger_and_blocks_new_work() {
        try (HikariDataSource dataSource = dataSource("reopen-active.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var firstProcess = new JdbcExportRunLedger(dataSource, CLOCK);
            ExportRun active = started("run-before-crash");
            firstProcess.tryStart(active);

            var reopened = new JdbcExportRunLedger(dataSource, CLOCK);

            assertThat(reopened.findIncomplete()).containsExactly(active);
            assertThat(reopened.tryStart(started("run-after-crash"))).isEmpty();
        }
    }

    @Test
    void incompatible_reuse_of_run_id_preserves_the_database_conflict() {
        try (HikariDataSource dataSource = dataSource("duplicate-run-id.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var ledger = new JdbcExportRunLedger(dataSource, CLOCK);
            ExportRun original = started("run-duplicate");
            ledger.tryStart(original);
            ExportRun incompatible = ExportRun.started(
                    original.runId(), original.profile(), "different-slice", PLAN_HASH, NOW);

            assertThatThrownBy(() -> ledger.tryStart(incompatible))
                    .isInstanceOf(DiagnosticException.class)
                    .hasCauseInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void incompatible_terminal_state_emits_export_diagnostic() {
        try (HikariDataSource dataSource = dataSource("transition-diagnostic.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var diagnostics = new CollectingDiagnosticSink();
            var ledger = new JdbcExportRunLedger(
                    dataSource, CLOCK, diagnostics, new DiagnosticFactory(CLOCK));
            ExportRun run = started("run-terminal");
            ledger.tryStart(run);
            ledger.transition(run.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.FAILED, null, "failed");

            assertThatThrownBy(() -> ledger.transition(run.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.STAGED, "b".repeat(64), null))
                    .isInstanceOf(DiagnosticException.class);
            assertThat(diagnostics.diagnostics())
                    .singleElement()
                    .satisfies(diagnostic -> {
                        assertThat(diagnostic.code()).isEqualTo(ExportDiagnosticCodes.STATE_TRANSITION_CONFLICT);
                        assertThat(diagnostic.context())
                                .containsEntry("expectedStatus", "STARTED")
                                .containsEntry("actualStatus", "FAILED")
                                .containsEntry("nextStatus", "STAGED");
                    });
        }
    }

    @Test
    void latestRunReadModelFiltersByProfileAndStatus() {
        try (HikariDataSource dataSource = dataSource("latest-run.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            var ledger = new JdbcExportRunLedger(dataSource, CLOCK);
            ExportRun failed = started("run-failed");
            ledger.tryStart(failed);
            ExportRun terminal = ledger.transition(
                    failed.runId(), ExportRunStatus.STARTED, ExportRunStatus.FAILED, null, "failed");

            assertThat(ledger.findLatest("reputation", ExportRunStatus.FAILED))
                    .contains(terminal);
            assertThat(ledger.findLatest("other", ExportRunStatus.FAILED)).isEmpty();
            assertThat(ledger.findLatest("reputation", ExportRunStatus.COMPLETED)).isEmpty();
        }
    }

    @Test
    void latestRunUsesChronologicalOrderForMixedInstantPrecision() {
        try (HikariDataSource dataSource = dataSource("latest-run-precision.db")) {
            new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
            MutableClock clock = new MutableClock(NOW);
            var ledger = new JdbcExportRunLedger(dataSource, clock);
            ExportRun first = started("run-first-at-exact-second");
            ledger.tryStart(first);
            ledger.transition(first.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.FAILED, null, "first");
            clock.advance(Duration.ofMillis(100));
            ExportRun second = ExportRun.started(
                    "run-second-with-fraction", "reputation",
                    "20260628T000000Z__run-second-with-fraction", PLAN_HASH, clock.instant());
            ledger.tryStart(second);
            ExportRun latest = ledger.transition(second.runId(), ExportRunStatus.STARTED,
                    ExportRunStatus.FAILED, null, "second");

            assertThat(ledger.findLatest("reputation", ExportRunStatus.FAILED)).contains(latest);
        }
    }

    private Optional<ExportRun> attemptStart(HikariDataSource dataSource,
                                             ExportRun run,
                                             CountDownLatch ready,
                                             CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to start concurrent export ledger attempt");
        }
        return new JdbcExportRunLedger(dataSource, CLOCK).tryStart(run);
    }

    private ExportRun started(String runId) {
        return ExportRun.started(
                runId, "reputation", "20260628T000000Z__" + runId, PLAN_HASH, NOW);
    }

    private HikariDataSource dataSource(String fileName) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 2));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
