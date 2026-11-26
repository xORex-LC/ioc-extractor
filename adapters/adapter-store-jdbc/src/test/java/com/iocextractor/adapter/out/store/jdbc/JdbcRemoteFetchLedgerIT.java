package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class JdbcRemoteFetchLedgerIT {

    private static final Instant MTIME = Instant.parse("2026-06-28T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-28T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void fetchedIdentityIsDurableAcrossLedgerInstances() {
        try (HikariDataSource dataSource = dataSource("fetch-durable.db")) {
            migrate(dataSource);
            RemoteObjectIdentity identity = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);

            new JdbcRemoteFetchLedger(dataSource).markFetched(identity, "var/inbox/a.htm", NOW);

            assertThat(new JdbcRemoteFetchLedger(dataSource).find(identity))
                    .hasValueSatisfying(record -> {
                        assertThat(record.status()).isEqualTo(RemoteFetchStatus.FETCHED);
                        assertThat(record.localPath()).isEqualTo("var/inbox/a.htm");
                        assertThat(record.fetchedAt()).isEqualTo(NOW);
                    });
        }
    }

    @Test
    void failedIdentityIncrementsAttemptsAndCanLaterBeFetched() {
        try (HikariDataSource dataSource = dataSource("fetch-retry.db")) {
            migrate(dataSource);
            JdbcRemoteFetchLedger ledger = new JdbcRemoteFetchLedger(dataSource);
            RemoteObjectIdentity identity = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);

            ledger.markFailed(identity, "timeout", NOW);
            ledger.markFailed(identity, "timeout again", NOW.plusSeconds(1));
            var fetched = ledger.markFetched(identity, "var/inbox/a.htm", NOW.plusSeconds(2));

            assertThat(fetched.status()).isEqualTo(RemoteFetchStatus.FETCHED);
            assertThat(fetched.attempts()).isEqualTo(2);
            assertThat(fetched.lastError()).isNull();
        }
    }

    @Test
    void samePathWithDifferentSizeIsDifferentIdentity() {
        try (HikariDataSource dataSource = dataSource("fetch-identity.db")) {
            migrate(dataSource);
            JdbcRemoteFetchLedger ledger = new JdbcRemoteFetchLedger(dataSource);
            RemoteObjectIdentity first = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);
            RemoteObjectIdentity second = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 43, MTIME);

            ledger.markFetched(first, "var/inbox/a.htm", NOW);

            assertThat(ledger.find(first)).isPresent();
            assertThat(ledger.find(second)).isEmpty();
        }
    }

    @Test
    void aFailedSourceDoesNotChangeAnotherSourcesFetchedState() {
        try (HikariDataSource dataSource = dataSource("fetch-source-isolation.db")) {
            migrate(dataSource);
            var first = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);
            var second = new RemoteObjectIdentity("source-b", "/incoming/a.htm", 42, MTIME);
            var ledger = new JdbcRemoteFetchLedger(dataSource);

            ledger.markFetched(first, "inbox/a-from-first.htm", NOW);
            ledger.markFailed(second, "timeout", NOW);
            ledger.markFailed(second, "timeout again", NOW.plusSeconds(1));
            var retried = ledger.markFetched(second, "inbox/a-from-second.htm", NOW.plusSeconds(2));

            assertThat(ledger.find(first)).hasValueSatisfying(record -> {
                assertThat(record.identity()).isEqualTo(first);
                assertThat(record.localPath()).isEqualTo("inbox/a-from-first.htm");
                assertThat(record.attempts()).isZero();
            });
            assertThat(retried.identity()).isEqualTo(second);
            assertThat(retried.attempts()).isEqualTo(2);
        }
    }

    @Test
    void migrationRetainsUnscopedHistoryAndHoldsOnlyMatchingFetchedTuples() {
        try (HikariDataSource dataSource = dataSource("fetch-upgrade.db")) {
            var migrations = ServiceSchemaMigrations.sqlite();
            new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 14)).migrate();
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                    INSERT INTO remote_fetch_ledger (remote_path, remote_size, remote_mtime,
                        status, local_path, attempts, fetched_at, updated_at)
                    VALUES ('/incoming/a.htm', 42, :mtime, 'FETCHED', 'old/a.htm', 3, :now, :now),
                           ('/incoming/b.htm', 42, :mtime, 'FAILED', NULL, 2, NULL, :now),
                           ('/incoming/c.htm', 42, :mtime, 'SKIPPED', NULL, 0, NULL, :now)
                    """).param("mtime", MTIME.toString()).param("now", NOW.toString()).update();

            migrate(dataSource);
            var ledger = new JdbcRemoteFetchLedger(dataSource);
            var first = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);
            var second = new RemoteObjectIdentity("source-b", "/incoming/a.htm", 42, MTIME);
            assertThat(ledger.find(first)).isEmpty();
            assertThat(ledger.find(second)).isEmpty();
            assertThat(ledger.requiresLegacyReview(first)).isTrue();
            assertThat(ledger.requiresLegacyReview(second)).isTrue();
            assertThat(ledger.requiresLegacyReview(new RemoteObjectIdentity("source-b", "/incoming/a.htm", 43, MTIME)))
                    .isFalse();
            assertThat(ledger.requiresLegacyReview(new RemoteObjectIdentity("source-b", "/incoming/b.htm", 42, MTIME)))
                    .isFalse();
            assertThat(ledger.requiresLegacyReview(new RemoteObjectIdentity("source-b", "/incoming/c.htm", 42, MTIME)))
                    .isFalse();
            assertThat(jdbc.sql("SELECT status FROM remote_fetch_ledger_legacy ORDER BY remote_path")
                    .query(String.class).list()).containsExactly("FETCHED", "FAILED", "SKIPPED");

            // An operator established ownership for A and separately approved a replay for B.
            jdbc.sql("""
                    INSERT INTO remote_fetch_ledger (source_id, remote_path, remote_size, remote_mtime,
                        status, local_path, attempts, last_error, fetched_at, updated_at)
                    SELECT 'source-a', remote_path, remote_size, remote_mtime,
                        status, local_path, attempts, last_error, fetched_at, updated_at
                    FROM remote_fetch_ledger_legacy WHERE remote_path = '/incoming/a.htm' AND status = 'FETCHED'
                    """).update();
            jdbc.sql("""
                    INSERT INTO remote_fetch_legacy_replay (source_id, remote_path, remote_size, remote_mtime, approved_at)
                    VALUES ('source-b', '/incoming/a.htm', 42, :mtime, :now)
                    """).param("mtime", MTIME.toString()).param("now", NOW.toString()).update();

            assertThat(ledger.find(first)).hasValueSatisfying(record -> {
                assertThat(record.status()).isEqualTo(RemoteFetchStatus.FETCHED);
                assertThat(record.localPath()).isEqualTo("old/a.htm");
                assertThat(record.attempts()).isEqualTo(3);
            });
            assertThat(ledger.find(second)).isEmpty();
            assertThat(ledger.requiresLegacyReview(second)).isFalse();
            var third = new RemoteObjectIdentity("source-c", "/incoming/a.htm", 42, MTIME);
            assertThat(ledger.requiresLegacyReview(third)).isTrue();
        }
    }

    private void migrate(HikariDataSource dataSource) {
        new SqliteUserVersionSchemaMigrator(dataSource, ServiceSchemaMigrations.sqlite()).migrate();
    }

    private HikariDataSource dataSource(String fileName) {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(
                new SqliteDataSourceSettings(
                        "service", "jdbc:sqlite:" + tempDir.resolve(fileName), "low-memory", 1, 1));
    }
}
