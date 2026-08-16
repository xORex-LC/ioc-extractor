package com.iocextractor;

import com.iocextractor.adapter.in.ingest.FileSourceHasher;
import com.iocextractor.adapter.out.store.jdbc.JdbcRemoteFetchLedger;
import com.iocextractor.application.ingest.IngestionStatus;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.port.in.ingest.IngestSourceCommand;
import com.iocextractor.application.port.in.ingest.IngestSourceResult;
import com.iocextractor.application.port.in.ingest.IngestSourceUseCase;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteFetchService;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end daemon ingest over the post-collapse direct-to-canonical path: a
 * source file is processed straight into JDBC canonical storage (with source
 * provenance) and a CSV projection, with no partition staging or aggregation
 * pass. Drives the use case directly for determinism (the file poller wiring is
 * covered by the runtime-mode context tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ioc.runtime.mode=daemon",
        "ioc.ingestion.dirs.inbox=target/daemon-e2e/inbox",
        "ioc.ingestion.dirs.processing=target/daemon-e2e/processing",
        "ioc.ingestion.dirs.done=target/daemon-e2e/done",
        "ioc.ingestion.dirs.failed=target/daemon-e2e/failed",
        "ioc.ingestion.ledger.path=target/daemon-e2e/ledger",
        "ioc.ingestion.detect.reconcile-interval=100ms",
        "ioc.ingestion.stability.quiet-period=0ms",
        "ioc.storage.service.url=jdbc:sqlite:target/daemon-e2e/ioc-service.db",
        "spring.main.banner-mode=off"
})
@ActiveProfiles("daemon-e2e")
class DaemonIngestE2ETest {

    @DynamicPropertySource
    static void pristineWorkdir(DynamicPropertyRegistry registry) {
        Path dir = Path.of("target/daemon-e2e");
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    IngestSourceUseCase ingestSourceUseCase;

    @Autowired
    @Qualifier("dataframeStorageDataSource")
    HikariDataSource dataframeStorageDataSource;

    @Autowired
    @Qualifier("serviceStorageDataSource")
    HikariDataSource serviceStorageDataSource;

    @Autowired
    ApplicationContext context;

    @Autowired
    FileSourceHasher fileSourceHasher;

    @Test
    void ingests_source_directly_into_canonical_storage_and_projection() throws Exception {
        // a source staged outside the watched inbox, so the poller cannot race the manual ingest
        Path staging = Files.createDirectories(Path.of("target/daemon-e2e/staging"));
        Path source = staging.resolve("e2e-source.html");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("golden/source.html")) {
            Files.write(source, in.readAllBytes());
        }
        Files.writeString(source, "\n<p>unique-original-e2e.example</p>\n",
                java.nio.file.StandardOpenOption.APPEND);

        IngestSourceResult result = ingestSourceUseCase.ingest(
                new IngestSourceCommand(source, new SourceKey("e2e-source"), Instant.parse("2026-06-25T00:00:00Z")));

        assertThat(result.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
        assertThat(result.duplicate()).isFalse();

        // canonical truth in the dataframe DB
        assertThat(count("SELECT COUNT(*) FROM masks")).isPositive();
        // provenance recorded against the ingest source key
        assertThat(count("SELECT COUNT(*) FROM masks_sources WHERE source_key = 'e2e-source'")).isPositive();
        // CSV projection refreshed from canonical truth
        Path projection = Path.of("target/daemon-e2e/masks_list_generated.csv");
        assertThat(Files.exists(projection)).isTrue();
        assertThat(Files.readAllLines(projection).size()).isGreaterThan(1); // header + at least one row
    }

    @Test
    void remote_fetch_lands_in_inbox_then_runs_the_normal_ingest_use_case() throws Exception {
        var pollingAdapters = context.getBeansOfType(SourcePollingChannelAdapter.class).values();
        pollingAdapters.forEach(SourcePollingChannelAdapter::stop);
        try {
            byte[] sourceBytes;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("golden/source.html")) {
                sourceBytes = in.readAllBytes();
            }
            Instant modifiedAt = Instant.parse("2026-06-28T00:00:00Z");
            FileTransport remote = readOnlyRemote("/incoming/remote-source.html", sourceBytes, modifiedAt);
            Path inbox = Path.of("target/daemon-e2e/inbox");
            RemoteFetchService fetcher = new RemoteFetchService(
                    remote,
                    new JdbcRemoteFetchLedger(serviceStorageDataSource),
                    List.of(new RemoteFetchSource(
                            "e2e-remote", "remote", "/incoming", List.of("*.html"), List.of())),
                    inbox,
                    new Retrier(new RetryPolicy(
                            1, Duration.ofMillis(1), 1.0d, Duration.ofMillis(1), false), ignored -> { }),
                    Clock.systemUTC(),
                    new com.iocextractor.application.sync.SyncDiagnosticReporter(
                            new com.iocextractor.diagnostics.sink.CollectingDiagnosticSink(), Clock.systemUTC()));

            var fetched = fetcher.fetch(new com.iocextractor.application.port.in.sync.RemoteFetchCommand(false));
            Path landed = inbox.resolve("remote-source.html");
            IngestSourceResult ingested = ingestSourceUseCase.ingest(
                    new IngestSourceCommand(landed, new SourceKey("remote-e2e"), modifiedAt));
            var duplicate = fetcher.fetch(new com.iocextractor.application.port.in.sync.RemoteFetchCommand(false));

            assertThat(fetched.fetched()).isOne();
            assertThat(ingested.status()).isEqualTo(IngestionStatus.SOURCE_ARCHIVED);
            assertThat(countForSourceKey("remote-e2e")).isPositive();
            assertThat(duplicate.skipped()).isOne();
        } finally {
            pollingAdapters.forEach(SourcePollingChannelAdapter::start);
        }
    }

    @Test
    void watchedInboxProcessesAnAtomicallyPublishedSourceAfterStartup() throws Exception {
        Path inbox = Files.createDirectories(Path.of("target/daemon-e2e/inbox"));
        Path partial = inbox.resolve("watched-source.part");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("golden/source.html")) {
            Files.write(partial, in.readAllBytes());
        }
        Files.writeString(partial, "\n<p>unique-watched-e2e.example</p>\n",
                java.nio.file.StandardOpenOption.APPEND);
        SourceKey key = fileSourceHasher.sha256(partial);
        Path published = inbox.resolve("watched-source.html");

        Files.move(partial, published, StandardCopyOption.ATOMIC_MOVE);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Path done = Path.of("target/daemon-e2e/done");
            assertThat(published).doesNotExist();
            assertThat(done).isDirectory();
            try (var archivedSources = Files.list(done)) {
                assertThat(archivedSources.map(path -> path.getFileName().toString()))
                        .anyMatch(name -> name.endsWith("__" + key.value() + "-watched-source.html"));
            }
            assertThat(countForSourceKey(key.value())).isPositive();
        });
    }

    private FileTransport readOnlyRemote(String path, byte[] content, Instant modifiedAt) {
        RemoteObject object = new RemoteObject(path, content.length, modifiedAt);
        return new FileTransport() {
            @Override
            public List<RemoteObject> list(String endpoint, String remotePath) {
                return List.of(object);
            }

            @Override
            public Optional<RemoteObject> stat(String endpoint, String remotePath) {
                return path.equals(remotePath) ? Optional.of(object) : Optional.empty();
            }

            @Override
            public void get(String endpoint, String remotePath, Path localDestination) {
                try {
                    Files.write(localDestination, content);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public void delete(String endpoint, String remotePath) {
                throw new AssertionError("fetch source must remain read-only");
            }

            @Override
            public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private long count(String sql) throws Exception {
        try (Connection connection = dataframeStorageDataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private long countForSourceKey(String sourceKey) throws Exception {
        try (Connection connection = dataframeStorageDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM masks_sources WHERE source_key = ?")) {
            statement.setString(1, sourceKey);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }
}
