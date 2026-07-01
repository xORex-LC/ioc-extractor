package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.sync.PublishRecord;
import com.iocextractor.application.sync.PublishStatus;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcPublishLedgerTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final String HASH = "b".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void ensurePendingIsIdempotentForSameSliceTargetBinding() {
        try (HikariDataSource dataSource = dataSource("publish-idempotent.db")) {
            migrate(dataSource);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
            PublishRecord pending = pending("slice-1", "target-a");

            PublishRecord first = ledger.ensurePending(pending);
            PublishRecord second = ledger.ensurePending(pending);

            assertThat(second).isEqualTo(first);
            assertThat(ledger.findBySlice("slice-1")).containsExactly(first);
        }
    }

    @Test
    void ensurePendingRejectsDuplicatePairWithDifferentBinding() {
        try (HikariDataSource dataSource = dataSource("publish-binding.db")) {
            migrate(dataSource);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
            ledger.ensurePending(pending("slice-1", "target-a"));

            assertThatThrownBy(() -> ledger.ensurePending(new PublishRecord(
                    "slice-1", "target-a", "profile", "other-slice-name", HASH,
                    "dist", "/out", PublishStatus.PENDING, 0, null, null, NOW, NOW)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different slice/target binding");
        }
    }

    @Test
    void casTransitionPreventsStaleWritersAndKeepsRetryableFailuresVisible() {
        try (HikariDataSource dataSource = dataSource("publish-cas.db")) {
            migrate(dataSource);
            MutableClock clock = new MutableClock(NOW);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, clock);
            ledger.ensurePending(pending("slice-1", "target-a"));

            PublishRecord inProgress = ledger.transition("slice-1", "target-a",
                    PublishStatus.PENDING, PublishStatus.IN_PROGRESS, null, null);
            clock.advanceSeconds(1);
            PublishRecord failed = ledger.transition("slice-1", "target-a",
                    PublishStatus.IN_PROGRESS, PublishStatus.FAILED, "timeout", null);

            assertThat(inProgress.status()).isEqualTo(PublishStatus.IN_PROGRESS);
            assertThat(failed.status()).isEqualTo(PublishStatus.FAILED);
            assertThat(failed.attempts()).isEqualTo(2);
            assertThat(failed.lastError()).isEqualTo("timeout");
            assertThat(ledger.findRetryable()).containsExactly(failed);
            assertThat(ledger.findAll()).containsExactly(failed);
            assertThatThrownBy(() -> ledger.transition("slice-1", "target-a",
                    PublishStatus.PENDING, PublishStatus.SUCCEEDED, null, "ok"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void succeededAndAbandonedAreTerminalForRetryReadModel() {
        try (HikariDataSource dataSource = dataSource("publish-terminal.db")) {
            migrate(dataSource);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
            ledger.ensurePending(pending("slice-1", "target-a"));
            ledger.transition("slice-1", "target-a", PublishStatus.PENDING, PublishStatus.ABANDONED,
                    "operator", null);
            ledger.ensurePending(pending("slice-2", "target-a"));
            ledger.transition("slice-2", "target-a", PublishStatus.PENDING, PublishStatus.IN_PROGRESS,
                    null, null);
            PublishRecord succeeded = ledger.transition("slice-2", "target-a",
                    PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED, null, "size-ok");

            assertThat(ledger.findRetryable()).isEmpty();
            assertThat(succeeded.remoteVerification()).isEqualTo("size-ok");
            assertThatThrownBy(() -> ledger.transition("slice-2", "target-a",
                    PublishStatus.SUCCEEDED, PublishStatus.FAILED, "late", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Illegal publish ledger transition");
        }
    }

    @Test
    void staleInProgressRowsAreRecoverableButFreshRowsAreNot() {
        try (HikariDataSource dataSource = dataSource("publish-stale-in-progress.db")) {
            migrate(dataSource);
            MutableClock clock = new MutableClock(NOW);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, clock);
            ledger.ensurePending(pending("slice-old", "target-a"));
            ledger.transition("slice-old", "target-a", PublishStatus.PENDING, PublishStatus.IN_PROGRESS,
                    null, null);
            clock.advanceSeconds(600);
            ledger.ensurePending(pending("slice-fresh", "target-a"));
            PublishRecord fresh = ledger.transition(
                    "slice-fresh", "target-a", PublishStatus.PENDING, PublishStatus.IN_PROGRESS,
                    null, null);

            assertThat(ledger.findRetryable(NOW.plusSeconds(300)))
                    .extracting(PublishRecord::sliceId)
                    .containsExactly("slice-old");
            assertThat(ledger.findRetryable(NOW.plusSeconds(601))).contains(fresh);
        }
    }

    @Test
    void countsStatusesWithSelectionFilters() {
        try (HikariDataSource dataSource = dataSource("publish-counts.db")) {
            migrate(dataSource);
            JdbcPublishLedger ledger = new JdbcPublishLedger(dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
            ledger.ensurePending(pending("slice-pending", "target-a"));
            ledger.ensurePending(pending("slice-done", "target-a"));
            ledger.transition("slice-done", "target-a", PublishStatus.PENDING, PublishStatus.IN_PROGRESS,
                    null, null);
            ledger.transition("slice-done", "target-a", PublishStatus.IN_PROGRESS, PublishStatus.SUCCEEDED,
                    null, "ok");
            ledger.ensurePending(pending("slice-other", "target-b"));

            var counts = ledger.countByStatus(
                    Optional.of("profile"), Optional.of("target-a"), Optional.of("dist"));

            assertThat(counts.pending()).isOne();
            assertThat(counts.succeeded()).isOne();
            assertThat(counts.failed()).isZero();
            assertThat(counts.abandoned()).isZero();
        }
    }

    private PublishRecord pending(String sliceId, String targetId) {
        return PublishRecord.pending(sliceId, targetId, "profile",
                "20260628T000000Z__" + sliceId, HASH, "dist", "/out", NOW);
    }

    private void migrate(HikariDataSource dataSource) {
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
    }

    private HikariDataSource dataSource(String fileName) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 1));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
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
