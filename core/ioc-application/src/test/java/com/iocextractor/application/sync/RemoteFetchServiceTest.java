package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.FetchRemoteObjectsCommand;
import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteFetchServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void duplicateRemoteIdentityIsSkippedWithoutTransportGet() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        FakeLedger ledger = new FakeLedger();
        ledger.markFetched(object.identity(), tempDir.resolve("a.htm").toString(), NOW);

        var result = service(transport, ledger).fetch(new RemoteFetchCommand(false));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.fetched()).isZero();
        assertThat(transport.getCalls).isZero();
    }

    @Test
    void failedDownloadLeavesNoFinalInboxFileAndNoFetchedMark() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        transport.failGet = true;
        FakeLedger ledger = new FakeLedger();

        var result = service(transport, ledger).fetch(new RemoteFetchCommand(false));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(tempDir.resolve("a.htm")).doesNotExist();
        assertThat(stagingFiles()).isEmpty();
        assertThat(ledger.find(object.identity()))
                .hasValueSatisfying(record -> assertThat(record.status()).isEqualTo(RemoteFetchStatus.FAILED));
    }

    @Test
    void failureAfterTempWriteBeforeMoveIsRetrySafe() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        transport.writeThenFail = true;
        FakeLedger ledger = new FakeLedger();

        var result = service(transport, ledger).fetch(new RemoteFetchCommand(false));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(tempDir.resolve("a.htm")).doesNotExist();
        assertThat(stagingFiles()).isEmpty();
        transport.writeThenFail = false;

        var retry = service(transport, ledger).fetch(new RemoteFetchCommand(false));

        assertThat(retry.fetched()).isEqualTo(1);
        assertThat(tempDir.resolve("a.htm")).hasContent("content:/share/a.htm");
    }

    @Test
    void occupiedLocalNameGetsStableSuffixWithoutOverwrite() throws Exception {
        Files.writeString(tempDir.resolve("a.htm"), "existing", StandardCharsets.UTF_8);
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        FakeLedger ledger = new FakeLedger();

        var result = service(transport, ledger).fetch(new RemoteFetchCommand(false));

        assertThat(result.fetched()).isEqualTo(1);
        assertThat(tempDir.resolve("a.htm")).hasContent("existing");
        assertThat(Files.list(tempDir)
                        .filter(path -> path.getFileName().toString().startsWith("a__"))
                        .filter(path -> path.getFileName().toString().endsWith(".htm"))
                        .toList())
                .singleElement()
                .satisfies(path -> assertThat(path).hasContent("content:/share/a.htm"));
    }

    @Test
    void includeExcludeFiltersAndSourceStaysReadOnly() throws Exception {
        RemoteObject accepted = object("/share/a.htm", 10);
        RemoteObject excluded = object("/share/b.tmp", 10);
        RemoteObject notIncluded = object("/share/c.pdf", 10);
        FakeTransport transport = new FakeTransport(List.of(accepted, excluded, notIncluded));
        FakeLedger ledger = new FakeLedger();
        RemoteFetchSource source = new RemoteFetchSource(
                "src", "endpoint", "/share", List.of("*.htm", "*.html"), List.of("*.tmp"));

        var result = service(transport, ledger, source).fetch(new RemoteFetchCommand(false));

        assertThat(result.fetched()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(tempDir.resolve("a.htm")).hasContent("content:/share/a.htm");
        assertThat(tempDir.resolve("b.tmp")).doesNotExist();
        assertThat(tempDir.resolve("c.pdf")).doesNotExist();
        assertThat(transport.deleteCalls).isZero();
    }

    @Test
    void sourceFilterScansOnlySelectedSource() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        FakeLedger ledger = new FakeLedger();
        List<RemoteFetchSource> sources = List.of(
                new RemoteFetchSource("one", "endpoint-one", "/one", List.of("*"), List.of()),
                new RemoteFetchSource("two", "endpoint-two", "/two", List.of("*"), List.of()));
        var service = new RemoteFetchService(
                transport, ledger, sources, tempDir,
                new Retrier(new RetryPolicy(1, Duration.ofMillis(1), 1.0d,
                        Duration.ofMillis(1), false), ignored -> { }), CLOCK);

        var result = service.fetch(new RemoteFetchCommand(Optional.of("two"), false));

        assertThat(result.fetched()).isOne();
        assertThat(transport.listCalls).containsExactly("endpoint-two:/two");
    }

    @Test
    void endpointFilterScansOnlySourcesOwnedBySelectedEndpoint() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        FakeLedger ledger = new FakeLedger();
        List<RemoteFetchSource> sources = List.of(
                new RemoteFetchSource("one", "endpoint-one", "/one", List.of("*"), List.of()),
                new RemoteFetchSource("two", "endpoint-two", "/two", List.of("*"), List.of()));
        var service = new RemoteFetchService(
                transport, ledger, sources, tempDir,
                new Retrier(new RetryPolicy(1, Duration.ofMillis(1), 1.0d,
                        Duration.ofMillis(1), false), ignored -> { }), CLOCK);

        var result = service.fetch(new RemoteFetchCommand(
                Optional.empty(), Optional.of("endpoint-one"), false));

        assertThat(result.fetched()).isOne();
        assertThat(transport.listCalls).containsExactly("endpoint-one:/one");
    }

    @Test
    void detectedObjectFetchDoesNotListRemoteSourceAgain() throws Exception {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of());
        FakeLedger ledger = new FakeLedger();

        var result = service(transport, ledger).fetch(new FetchRemoteObjectsCommand(
                "src", "endpoint", "/share", List.of(object), false));

        assertThat(result.fetched()).isOne();
        assertThat(transport.listCalls).isEmpty();
        assertThat(transport.getCalls).isOne();
        assertThat(tempDir.resolve("a.htm")).hasContent("content:/share/a.htm");
        assertThat(ledger.find(object.identity()))
                .hasValueSatisfying(record -> assertThat(record.status()).isEqualTo(RemoteFetchStatus.FETCHED));
    }

    @Test
    void unexpectedProgrammingFailureIsNotConvertedToFetchLedgerFailure() {
        RemoteObject object = object("/share/a.htm", 10);
        FakeTransport transport = new FakeTransport(List.of(object));
        transport.unexpectedGetFailure = new IllegalArgumentException("adapter contract violation");
        FakeLedger ledger = new FakeLedger();

        assertThatThrownBy(() -> service(transport, ledger).fetch(new RemoteFetchCommand(false)))
                .isSameAs(transport.unexpectedGetFailure);
        assertThat(ledger.find(object.identity())).isEmpty();
        assertThat(tempDir.resolve("a.htm")).doesNotExist();
        assertThat(stagingFiles()).isEmpty();
    }

    private RemoteFetchService service(FakeTransport transport, FakeLedger ledger) {
        return service(transport, ledger, new RemoteFetchSource(
                "src", "endpoint", "/share", List.of("*"), List.of("*.part", ".*")));
    }

    private RemoteFetchService service(FakeTransport transport, FakeLedger ledger, RemoteFetchSource source) {
        return new RemoteFetchService(
                transport,
                ledger,
                List.of(source),
                tempDir,
                new Retrier(new RetryPolicy(1, Duration.ofMillis(1), 1.0d,
                        Duration.ofMillis(1), false), ignored -> { }),
                CLOCK);
    }

    private RemoteObject object(String path, long size) {
        return new RemoteObject(path, size, NOW);
    }

    private List<Path> stagingFiles() {
        Path staging = tempDir.resolve(".sync-staging");
        if (!Files.exists(staging)) {
            return List.of();
        }
        try {
            return Files.list(staging).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeTransport implements FileTransport {
        private final List<RemoteObject> objects;
        private int getCalls;
        private int deleteCalls;
        private final List<String> listCalls = new java.util.ArrayList<>();
        private boolean failGet;
        private boolean writeThenFail;
        private RuntimeException unexpectedGetFailure;

        private FakeTransport(List<RemoteObject> objects) {
            this.objects = List.copyOf(objects);
        }

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            listCalls.add(endpoint + ":" + remotePath);
            return objects;
        }

        @Override
        public Optional<RemoteObject> stat(String endpoint, String remotePath) {
            return Optional.empty();
        }

        @Override
        public void get(String endpoint, String remotePath, Path localDestination) {
            getCalls++;
            if (unexpectedGetFailure != null) {
                throw unexpectedGetFailure;
            }
            if (failGet) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "download failed");
            }
            try {
                Files.writeString(localDestination, "content:" + remotePath, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (writeThenFail) {
                throw new RemoteTransportException(RemoteErrorKind.TRANSIENT, "failed after write");
            }
        }

        @Override
        public void delete(String endpoint, String remotePath) {
            deleteCalls++;
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            throw new UnsupportedOperationException("publish is not used by fetch service");
        }
    }

    private static final class FakeLedger implements RemoteFetchLedger {
        private final Map<RemoteObjectIdentity, RemoteFetchRecord> records = new LinkedHashMap<>();

        @Override
        public Optional<RemoteFetchRecord> find(RemoteObjectIdentity identity) {
            return Optional.ofNullable(records.get(identity));
        }

        @Override
        public RemoteFetchRecord markFetched(RemoteObjectIdentity identity, String localPath, Instant fetchedAt) {
            RemoteFetchRecord record = new RemoteFetchRecord(
                    identity, RemoteFetchStatus.FETCHED, localPath, 0, null, fetchedAt, fetchedAt);
            records.put(identity, record);
            return record;
        }

        @Override
        public RemoteFetchRecord markSkipped(RemoteObjectIdentity identity, String reason, Instant skippedAt) {
            RemoteFetchRecord record = new RemoteFetchRecord(
                    identity, RemoteFetchStatus.SKIPPED, null, 0, reason, null, skippedAt);
            records.put(identity, record);
            return record;
        }

        @Override
        public RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt) {
            RemoteFetchRecord previous = records.get(identity);
            RemoteFetchRecord record = new RemoteFetchRecord(
                    identity,
                    RemoteFetchStatus.FAILED,
                    null,
                    previous == null ? 1 : previous.attempts() + 1,
                    reason,
                    null,
                    failedAt);
            records.put(identity, record);
            return record;
        }
    }
}
