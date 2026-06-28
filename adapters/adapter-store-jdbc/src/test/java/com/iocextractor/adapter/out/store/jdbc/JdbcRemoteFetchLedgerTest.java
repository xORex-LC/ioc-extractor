package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRemoteFetchLedgerTest {

    private static final Instant MTIME = Instant.parse("2026-06-28T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-28T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void fetchedIdentityIsDurableAcrossLedgerInstances() {
        try (HikariDataSource dataSource = dataSource("fetch-durable.db")) {
            migrate(dataSource);
            RemoteObjectIdentity identity = new RemoteObjectIdentity("/incoming/a.htm", 42, MTIME);

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
            RemoteObjectIdentity identity = new RemoteObjectIdentity("/incoming/a.htm", 42, MTIME);

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
            RemoteObjectIdentity first = new RemoteObjectIdentity("/incoming/a.htm", 42, MTIME);
            RemoteObjectIdentity second = new RemoteObjectIdentity("/incoming/a.htm", 43, MTIME);

            ledger.markFetched(first, "var/inbox/a.htm", NOW);

            assertThat(ledger.find(first)).isPresent();
            assertThat(ledger.find(second)).isEmpty();
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
