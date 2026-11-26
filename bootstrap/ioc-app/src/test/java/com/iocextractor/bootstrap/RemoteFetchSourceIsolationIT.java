package com.iocextractor.bootstrap;

import com.iocextractor.adapter.out.store.jdbc.JdbcRemoteFetchLedger;
import com.iocextractor.adapter.out.store.jdbc.ServiceSchemaMigrations;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceFactory;
import com.iocextractor.adapter.out.store.jdbc.SqliteDataSourceSettings;
import com.iocextractor.adapter.out.store.jdbc.SqlitePragmaPolicy;
import com.iocextractor.adapter.out.store.jdbc.SqliteUserVersionSchemaMigrator;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchResult;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchInFlightRegistry;
import com.iocextractor.application.sync.RemoteFetchService;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.iocextractor.application.sync.SyncDiagnosticReporter;
import com.iocextractor.application.tck.junit.IntegrationTest;
import com.iocextractor.platform.concurrent.BoundedKeyedSerialExecutor;
import com.iocextractor.platform.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.events.ControlEventObserver;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IntegrationTest
class RemoteFetchSourceIsolationIT {

    @TempDir
    private Path workspace;

    @Test
    @Timeout(30)
    void equalMetadataFromTwoEndpointsLandsBothFilesAndSurvivesRestart() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
        var object = new RemoteObject("/incoming/report.html", 3, clock.instant());
        var sources = List.of(source("source-a", "endpoint-a"), source("source-b", "endpoint-b"));
        var transport = mock(FileTransport.class);
        when(transport.list(anyString(), eq("/incoming"))).thenReturn(List.of(object));
        var bothDownloads = new CountDownLatch(2);
        var firstDownload = new CountDownLatch(1);
        doAnswer(call -> {
            String endpoint = call.getArgument(0);
            Path destination = call.getArgument(2);
            firstDownload.countDown();
            bothDownloads.countDown();
            assertThat(bothDownloads.await(8, TimeUnit.SECONDS)).isTrue();
            Files.writeString(destination, "endpoint-a".equals(endpoint) ? "one" : "two");
            return null;
        }).when(transport).get(anyString(), eq(object.path()), any(Path.class));
        var reporter = new SyncDiagnosticReporter(new CollectingDiagnosticSink(), clock);
        var retrier = new Retrier(new RetryPolicy(
                1, Duration.ofMillis(1), 1.0, Duration.ofMillis(1), false), ignored -> { });
        var command = new RemoteFetchCommand(false);
        Path inbox = workspace.resolve("inbox");

        try (HikariDataSource db = database()) {
            new SqliteUserVersionSchemaMigrator(db, ServiceSchemaMigrations.sqlite()).migrate();
            var ledger = new JdbcRemoteFetchLedger(db);
            var inFlight = new RemoteFetchInFlightRegistry();
            var monitor = new RemoteSourceMonitor(transport, ledger, inFlight, sources, 10, clock, retrier, reporter);
            var fetcher = new RemoteFetchService(transport, ledger, sources, inbox, retrier, clock, reporter);
            var workers = Executors.newFixedThreadPool(2);
            try (var executor = new BoundedKeyedSerialExecutor(workers, 8)) {
                var listener = new RemoteChangeFetchListener(fetcher, executor, mock(ControlEventObserver.class),
                        new SyncHealthState(clock), inFlight);

                var detected = monitor.detect(command);
                assertThat(detected).extracting(RemoteChangeBatchDetected::sourceId)
                        .containsExactly("source-a", "source-b");
                listener.onRemoteChangeBatchDetected(detected.getFirst());
                assertThat(firstDownload.await(5, TimeUnit.SECONDS)).isTrue();
                var whileFirstInFlight = monitor.detect(command);
                assertThat(whileFirstInFlight).extracting(RemoteChangeBatchDetected::sourceId)
                        .containsExactly("source-b");
                listener.onRemoteChangeBatchDetected(whileFirstInFlight.getFirst());
                executor.shutdown();
                assertThat(executor.awaitTermination(Duration.ofSeconds(10))).isTrue();
            } finally {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            }
        }

        try (HikariDataSource reopened = database()) {
            var ledger = new JdbcRemoteFetchLedger(reopened);
            Path first = Path.of(ledger.find(object.identity("source-a")).orElseThrow().localPath());
            Path second = Path.of(ledger.find(object.identity("source-b")).orElseThrow().localPath());
            assertThat(first).isNotEqualTo(second).hasContent("one");
            assertThat(second).hasContent("two");
            assertThat(first.getFileName().toString()).matches("report__[a-f0-9]{32}\\.html");
            assertThat(second.getFileName().toString()).matches("report__[a-f0-9]{32}\\.html");
            var monitor = new RemoteSourceMonitor(transport, ledger, new RemoteFetchInFlightRegistry(),
                    sources, 10, clock, retrier, reporter);
            assertThat(monitor.detect(command)).isEmpty();
            var fetcher = new RemoteFetchService(transport, ledger, sources, inbox, retrier, clock, reporter);
            assertThat(fetcher.fetch(command)).isEqualTo(new RemoteFetchResult(0, 2, 0));
        }
        verify(transport, times(2)).get(anyString(), eq(object.path()), any(Path.class));
    }

    private RemoteFetchSource source(String id, String endpoint) {
        return new RemoteFetchSource(id, endpoint, "/incoming", List.of("*.html"), List.of());
    }

    private HikariDataSource database() {
        return new SqliteDataSourceFactory(new SqlitePragmaPolicy()).create(new SqliteDataSourceSettings(
                "service", "jdbc:sqlite:" + workspace.resolve("service.db"), "low-memory", 1, 1));
    }
}
