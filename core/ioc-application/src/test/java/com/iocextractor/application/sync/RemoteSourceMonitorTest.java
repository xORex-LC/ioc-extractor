package com.iocextractor.application.sync;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteSourceMonitorTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void detectsOnlyIncludedNonFetchedRemoteIdentities() {
        RemoteObject accepted = object("/incoming/a.htm", 1);
        RemoteObject fetched = object("/incoming/already.htm", 2);
        RemoteObject excluded = object("/incoming/b.part", 3);
        FakeTransport transport = new FakeTransport(List.of(accepted, fetched, excluded));
        FakeLedger ledger = new FakeLedger();
        ledger.markFetched(fetched.identity(), "var/inbox/already.htm", NOW);

        List<RemoteChangeBatchDetected> events = monitor(transport, ledger, 10)
                .detect(new RemoteFetchCommand(false));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.sourceId()).isEqualTo("source");
            assertThat(event.endpoint()).isEqualTo("endpoint");
            assertThat(event.remotePath()).isEqualTo("/incoming");
            assertThat(event.objects()).containsExactly(accepted);
            assertThat(event.metadata().eventType()).isEqualTo(RemoteChangeBatchDetected.EVENT_TYPE);
        });
    }

    @Test
    void boundedBatchSplitsLargeDetectionResult() {
        FakeTransport transport = new FakeTransport(List.of(
                object("/incoming/a.htm", 1),
                object("/incoming/b.htm", 2),
                object("/incoming/c.htm", 3)));

        List<RemoteChangeBatchDetected> events = monitor(transport, new FakeLedger(), 2)
                .detect(new RemoteFetchCommand(false));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).objects()).hasSize(2);
        assertThat(events.get(1).objects()).hasSize(1);
    }

    @Test
    void sourceAndEndpointSelectionLimitRemoteListing() {
        RemoteFetchSource one = source("one", "endpoint-one", "/one");
        RemoteFetchSource two = source("two", "endpoint-two", "/two");
        FakeTransport transport = new FakeTransport(List.of(object("/two/a.htm", 1)));
        RemoteSourceMonitor monitor = new RemoteSourceMonitor(
                transport, new FakeLedger(), new RemoteFetchInFlightRegistry(),
                List.of(one, two), 10, CLOCK);

        List<RemoteChangeBatchDetected> events = monitor.detect(new RemoteFetchCommand(
                Optional.of("two"), Optional.empty(), false));

        assertThat(events).singleElement()
                .extracting(RemoteChangeBatchDetected::sourceId)
                .isEqualTo("two");
        assertThat(transport.listCalls).containsExactly("endpoint-two:/two");
    }

    @Test
    void suppressesClaimedIdentityUntilExecutionReleasesIt() {
        RemoteObject object = object("/incoming/slow.htm", 1);
        FakeTransport transport = new FakeTransport(List.of(object));
        RemoteFetchInFlightRegistry inFlight = new RemoteFetchInFlightRegistry();
        RemoteSourceMonitor monitor = new RemoteSourceMonitor(
                transport, new FakeLedger(), inFlight,
                List.of(source("source", "endpoint", "/incoming")), 10, CLOCK);
        List<RemoteChangeBatchDetected> first = monitor.detect(new RemoteFetchCommand(false));
        inFlight.claim(first.getFirst().objects());

        List<RemoteChangeBatchDetected> whileInFlight = monitor.detect(new RemoteFetchCommand(false));
        inFlight.release(first.getFirst().objects());
        List<RemoteChangeBatchDetected> afterRelease = monitor.detect(new RemoteFetchCommand(false));

        assertThat(whileInFlight).isEmpty();
        assertThat(afterRelease).singleElement()
                .satisfies(event -> assertThat(event.objects()).containsExactly(object));
    }

    private RemoteSourceMonitor monitor(FakeTransport transport, FakeLedger ledger, int maxBatchSize) {
        return new RemoteSourceMonitor(
                transport, ledger, new RemoteFetchInFlightRegistry(),
                List.of(source("source", "endpoint", "/incoming")),
                maxBatchSize, CLOCK);
    }

    private RemoteFetchSource source(String id, String endpoint, String remotePath) {
        return new RemoteFetchSource(id, endpoint, remotePath, List.of("*.htm"), List.of("*.part"));
    }

    private RemoteObject object(String path, long size) {
        return new RemoteObject(path, size, NOW);
    }

    private static final class FakeTransport implements FileTransport {
        private final List<RemoteObject> objects;
        private final java.util.ArrayList<String> listCalls = new java.util.ArrayList<>();

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
            throw new UnsupportedOperationException("monitor must not download");
        }

        @Override
        public void delete(String endpoint, String remotePath) {
            throw new UnsupportedOperationException("monitor must not delete");
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            throw new UnsupportedOperationException("monitor must not publish");
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
            throw new UnsupportedOperationException();
        }

        @Override
        public RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt) {
            throw new UnsupportedOperationException();
        }
    }
}
