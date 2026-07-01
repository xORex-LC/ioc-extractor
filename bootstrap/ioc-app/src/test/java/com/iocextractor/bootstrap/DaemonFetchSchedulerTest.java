package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.RemoteFetchCommand;
import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchRecord;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonFetchSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sourceFailureIsIsolatedAndRetriedOnNextTick() {
        FakeTransport transport = new FakeTransport();
        transport.failEndpointOnce("endpoint-one");
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        DaemonFetchScheduler scheduler = scheduler(transport, publisher, source("one"), source("two"));

        scheduler.runOnce();
        scheduler.runOnce();

        assertThat(transport.listCalls)
                .containsExactly("endpoint-one:/one", "endpoint-two:/two",
                        "endpoint-one:/one", "endpoint-two:/two");
    }

    @Test
    void slowCycleDoesNotOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport();
        transport.blockList(entered, release);
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        DaemonFetchScheduler scheduler = scheduler(transport, publisher, source("one"));
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.runOnce();
        release.countDown();
        first.join(1000);

        assertThat(transport.listCalls).containsExactly("endpoint-one:/one");
    }

    @Test
    void lifecycleStartsBeforeExportAndStopsCleanly() {
        DaemonFetchScheduler scheduler = scheduler(new FakeTransport(), new RecordingControlEventPublisher(),
                source("one"));

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(scheduler.getPhase()).isLessThan(DaemonExportScheduler.PHASE);
        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void publishesDetectedRemoteChangeBatch() {
        RemoteObject object = object("/one/a.htm", 10);
        FakeTransport transport = new FakeTransport(Map.of("endpoint-one:/one", List.of(object)));
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        DaemonFetchScheduler scheduler = scheduler(transport, publisher, source("one"));

        scheduler.runOnce();

        assertThat(publisher.events()).singleElement()
                .isInstanceOfSatisfying(RemoteChangeBatchDetected.class, event -> {
                    assertThat(event.sourceId()).isEqualTo("one");
                    assertThat(event.endpoint()).isEqualTo("endpoint-one");
                    assertThat(event.objects()).containsExactly(object);
                });
    }

    @Test
    void recordsIdleDetectionToHealthState() {
        SyncHealthState state = new SyncHealthState(CLOCK);
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")),
                monitor(new FakeTransport(), source("one")),
                new RecordingControlEventPublisher(),
                registry(),
                state,
                Duration.ofHours(1));

        scheduler.runOnce();

        assertThat(state.fetchSnapshots().get("one"))
                .extracting(snapshot -> snapshot.fetched(),
                        snapshot -> snapshot.skipped(),
                        snapshot -> snapshot.failed())
                .containsExactly(0, 0, 0);
    }

    @Test
    void closesIdleTransportsAfterCycleFailure() {
        AtomicInteger idleCloseCalls = new AtomicInteger();
        FakeTransport transport = new FakeTransport();
        transport.failEndpointOnce("endpoint-one");
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")),
                monitor(transport, source("one")),
                new RecordingControlEventPublisher(),
                registry(idleCloseCalls::incrementAndGet),
                healthState(),
                Duration.ofHours(1));

        scheduler.runOnce();

        assertThat(idleCloseCalls).hasValue(1);
    }

    private DaemonFetchScheduler scheduler(FakeTransport transport,
                                           RecordingControlEventPublisher publisher,
                                           RemoteFetchSource... sources) {
        List<RemoteFetchSource> configuredSources = List.of(sources);
        return new DaemonFetchScheduler(
                configuredSources,
                new RemoteSourceMonitor(transport, new FakeLedger(), configuredSources, 10, CLOCK),
                publisher,
                registry(),
                healthState(),
                Duration.ofHours(1));
    }

    private RemoteSourceMonitor monitor(FileTransport transport, RemoteFetchSource... sources) {
        return new RemoteSourceMonitor(transport, new FakeLedger(), List.of(sources), 10, CLOCK);
    }

    private RemoteFetchSource source(String id) {
        return new RemoteFetchSource(id, "endpoint-" + id, "/" + id, List.of("*"), List.of());
    }

    private RemoteObject object(String path, long size) {
        return new RemoteObject(path, size, NOW);
    }

    private TransportRegistry registry() {
        return registry(() -> { });
    }

    private TransportRegistry registry(Runnable idleMaintenance) {
        FakeTransport transport = new FakeTransport();
        return new TransportRegistry(List.of(new TransportRegistry.Binding(
                "endpoint-one", transport, idleMaintenance, () -> { })));
    }

    private SyncHealthState healthState() {
        return new SyncHealthState(Clock.systemUTC());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class FakeTransport implements FileTransport {
        private final Map<String, List<RemoteObject>> objects;
        private final List<String> listCalls = new ArrayList<>();
        private final Map<String, AtomicInteger> failingEndpoints = new LinkedHashMap<>();
        private CountDownLatch entered;
        private CountDownLatch release;

        private FakeTransport() {
            this(Map.of());
        }

        private FakeTransport(Map<String, List<RemoteObject>> objects) {
            this.objects = objects;
        }

        private void failEndpointOnce(String endpoint) {
            failingEndpoints.put(endpoint, new AtomicInteger(1));
        }

        private void blockList(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            listCalls.add(endpoint + ":" + remotePath);
            if (entered != null) {
                entered.countDown();
                await(release);
            }
            AtomicInteger remainingFailures = failingEndpoints.get(endpoint);
            if (remainingFailures != null && remainingFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("unreachable");
            }
            return objects.getOrDefault(endpoint + ":" + remotePath, List.of());
        }

        @Override
        public Optional<RemoteObject> stat(String endpoint, String remotePath) {
            return Optional.empty();
        }

        @Override
        public void get(String endpoint, String remotePath, Path localDestination) {
        }

        @Override
        public void delete(String endpoint, String remotePath) {
        }

        @Override
        public PublishReceipt publishAtomically(PublishAtomicallyRequest request) {
            return new PublishReceipt("unused", "unused");
        }

    }

    private static final class FakeLedger implements RemoteFetchLedger {
        @Override
        public Optional<RemoteFetchRecord> find(RemoteObjectIdentity identity) {
            return Optional.empty();
        }

        @Override
        public RemoteFetchRecord markFetched(RemoteObjectIdentity identity, String localPath, Instant fetchedAt) {
            return record(identity, RemoteFetchStatus.FETCHED, localPath, null, fetchedAt);
        }

        @Override
        public RemoteFetchRecord markSkipped(RemoteObjectIdentity identity, String reason, Instant skippedAt) {
            return record(identity, RemoteFetchStatus.SKIPPED, null, reason, null);
        }

        @Override
        public RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt) {
            return record(identity, RemoteFetchStatus.FAILED, null, reason, null);
        }

        private RemoteFetchRecord record(RemoteObjectIdentity identity,
                                         RemoteFetchStatus status,
                                         String localPath,
                                         String lastError,
                                         Instant fetchedAt) {
            return new RemoteFetchRecord(identity, status, localPath, 0, lastError, fetchedAt, NOW);
        }
    }
}
