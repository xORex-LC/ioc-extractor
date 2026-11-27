package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(ints = {14, 16})
    void migrationRemovesUnscopedFetchStateAndKeepsScopedRecords(int previousVersion) {
        try (HikariDataSource dataSource = dataSource("fetch-upgrade.db")) {
            var migrations = ServiceSchemaMigrations.sqlite();
            new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 14)).migrate();
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("""
                    INSERT INTO remote_fetch_ledger (remote_path, remote_size, remote_mtime,
                        status, local_path, attempts, fetched_at, updated_at)
                    VALUES ('/incoming/a.htm', 42, :mtime, 'FETCHED', 'old/a.htm', 3, :now, :now)
                    """).param("mtime", MTIME.toString()).param("now", NOW.toString()).update();
            var first = new RemoteObjectIdentity("source-a", "/incoming/a.htm", 42, MTIME);
            var second = new RemoteObjectIdentity("source-b", "/incoming/a.htm", 42, MTIME);
            var ledger = new JdbcRemoteFetchLedger(dataSource);
            if (previousVersion == 16) {
                new SqliteUserVersionSchemaMigrator(dataSource, migrations.subList(0, 16)).migrate();
                ledger.markFetched(first, "inbox/a-from-first.htm", NOW);
                jdbc.sql("""
                        INSERT INTO remote_fetch_legacy_replay (
                            source_id, remote_path, remote_size, remote_mtime, approved_at)
                        VALUES ('source-b', '/incoming/a.htm', 42, :mtime, :now)
                        """).param("mtime", MTIME.toString()).param("now", NOW.toString()).update();
            }

            var result = new SqliteUserVersionSchemaMigrator(dataSource, migrations).migrate();

            assertThat(result.previousVersion()).isEqualTo(previousVersion);
            assertThat(result.currentVersion()).isEqualTo(18);
            assertThat(jdbc.sql("""
                    SELECT name FROM sqlite_schema
                    WHERE type = 'table' AND name IN ('remote_fetch_ledger_legacy', 'remote_fetch_legacy_replay')
                    """).query(String.class).list()).isEmpty();
            if (previousVersion == 16) {
                assertThat(ledger.find(first)).hasValueSatisfying(record -> {
                    assertThat(record.identity()).isEqualTo(first);
                    assertThat(record.status()).isEqualTo(RemoteFetchStatus.FETCHED);
                    assertThat(record.localPath()).isEqualTo("inbox/a-from-first.htm");
                });
            } else {
                assertThat(ledger.find(first)).isEmpty();
            }
            assertThat(ledger.find(second)).isEmpty();
            assertThat(ledger.markFetched(second, "inbox/a-from-second.htm", NOW).identity()).isEqualTo(second);
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
