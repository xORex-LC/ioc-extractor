package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteChangeBatchDetected;
import com.iocextractor.application.sync.RemoteFetchInFlightRegistry;
import com.iocextractor.application.sync.RemoteFetchRecord;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.iocextractor.application.sync.SyncDiagnosticReporter;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.events.ControlEventPublisher;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemoteFetchDetectionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void publishesDetectedBatchAndRecordsDetectionOutcome() {
        RemoteObject object = object("/one/a.htm", 10);
        FakeTransport transport = new FakeTransport(Map.of("endpoint-one:/one", List.of(object)));
        RecordingControlEventPublisher publisher = new RecordingControlEventPublisher();
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        AtomicInteger closeIdleCalls = new AtomicInteger();
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                transport, publisher, healthState, closeIdleCalls::incrementAndGet, Duration.ZERO, source("one"))) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PERIODIC);

            waitUntil(() -> !publisher.events().isEmpty()
                    && healthState.fetchDetectionSnapshots().containsKey("one")
                    && closeIdleCalls.get() == 1);
            assertThat(publisher.events()).singleElement()
                    .isInstanceOfSatisfying(RemoteChangeBatchDetected.class, event ->
                            assertThat(event.objects()).containsExactly(object));
            assertThat(healthState.fetchDetectionSnapshots().get("one"))
                    .extracting(snapshot -> snapshot.reason(), snapshot -> snapshot.detectedObjects(),
                            snapshot -> snapshot.status())
                    .containsExactly("PERIODIC", 1, SyncOperationalStatus.UP);
            assertThat(closeIdleCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void watchEstablishedRunsDetectionWithoutDebounceDelay() {
        FakeTransport transport = new FakeTransport();
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                transport, new RecordingControlEventPublisher(), healthState, () -> { },
                Duration.ofHours(1), source("one"))) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.WATCH_ESTABLISHED);

            waitUntil(() -> healthState.fetchDetectionSnapshots().containsKey("one"));
            assertThat(healthState.fetchDetectionSnapshots().get("one").reason())
                    .isEqualTo("WATCH_ESTABLISHED");
        }
    }

    @Test
    void pushSignalsWhileRunningCoalesceIntoOneTrailingDetection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport transport = new FakeTransport();
        transport.blockList(entered, release);
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                transport, new RecordingControlEventPublisher(), healthState, () -> { },
                Duration.ofMillis(10), source("one"))) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PUSH);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PUSH);
            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PUSH);
            release.countDown();

            waitUntil(() -> transport.listCalls.size() == 2);
            assertThat(transport.listCalls).containsExactly("endpoint-one:/one", "endpoint-one:/one");
            assertThat(healthState.fetchDetectionSnapshots().get("one").coalescedSignals())
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void publisherFailureDoesNotMakeDetectionFail() {
        FakeTransport transport = new FakeTransport(Map.of("endpoint-one:/one", List.of(object("/one/a.htm", 10))));
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                transport, event -> {
                    throw new IllegalStateException("publisher unavailable");
                }, healthState, () -> { }, Duration.ZERO, source("one"))) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PERIODIC);

            waitUntil(() -> healthState.fetchDetectionSnapshots().containsKey("one"));
            assertThat(healthState.fetchDetectionSnapshots().get("one").status())
                    .isEqualTo(SyncOperationalStatus.UP);
        }
    }

    @Test
    void recordsWatchStateTransitions() {
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                new FakeTransport(), new RecordingControlEventPublisher(), healthState, () -> { },
                Duration.ZERO, source("one"))) {

            coordinator.recordWatchDisabled(source("one"));
            coordinator.recordWatchFailure(source("one"), new IllegalStateException("connection reset"));
            coordinator.recordWatchEstablished(source("one"));

            waitUntil(() -> healthState.remoteChangeWatchSnapshots().get("one").status()
                    == RemoteChangeWatchStatus.ACTIVE);
            assertThat(healthState.remoteChangeWatchSnapshots().get("one"))
                    .extracting(snapshot -> snapshot.status(), snapshot -> snapshot.reconnects(),
                            snapshot -> snapshot.reArms())
                    .containsExactly(RemoteChangeWatchStatus.ACTIVE, 1L, 0L);
        }
    }

    @Test
    void detectionThreadNameIncludesSourceAndEndpoint() {
        FakeTransport transport = new FakeTransport();
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(
                transport, new RecordingControlEventPublisher(), new SyncHealthState(CLOCK), () -> { },
                Duration.ZERO, source("one"))) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.WATCH_ESTABLISHED);

            waitUntil(() -> !transport.listThreadNames.isEmpty());
            assertThat(transport.listThreadNames).singleElement()
                    .satisfies(name -> assertThat(name)
                            .contains("ioc-sync-fetch-detection", "one", "endpoint-one"));
        }
    }

    @Test
    void staleScheduledDetectionDoesNotClearNewerImmediateTrigger() {
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        FakeTransport transport = new FakeTransport();
        SyncHealthState healthState = new SyncHealthState(CLOCK);
        try (RemoteFetchDetectionCoordinator coordinator = new RemoteFetchDetectionCoordinator(
                List.of(source("one")),
                new RemoteSourceMonitor(transport, new FakeLedger(), new RemoteFetchInFlightRegistry(),
                        List.of(source("one")), 10, CLOCK, retrier(), reporter()),
                new RecordingControlEventPublisher(),
                registry(() -> { }),
                healthState,
                Map.of("one", Duration.ofHours(1)),
                executor)) {

            coordinator.trigger(source("one"), RemoteFetchDetectionReason.PUSH);
            ManualScheduledFuture stale = executor.tasks.get(0);
            coordinator.trigger(source("one"), RemoteFetchDetectionReason.WATCH_ESTABLISHED);
            ManualScheduledFuture current = executor.tasks.get(1);

            stale.run();
            current.run();

            assertThat(transport.listCalls).containsExactly("endpoint-one:/one");
            assertThat(healthState.fetchDetectionSnapshots().get("one").reason())
                    .isEqualTo("WATCH_ESTABLISHED");
        }
    }

    private RemoteFetchDetectionCoordinator coordinator(FakeTransport transport,
                                                       ControlEventPublisher publisher,
                                                       SyncHealthState healthState,
                                                       Runnable closeIdle,
                                                       Duration debounce,
                                                       RemoteFetchSource... sources) {
        List<RemoteFetchSource> configuredSources = List.of(sources);
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(
                Math.max(1, configuredSources.size()));
        return new RemoteFetchDetectionCoordinator(
                configuredSources,
                new RemoteSourceMonitor(transport, new FakeLedger(), new RemoteFetchInFlightRegistry(),
                        configuredSources, 10, CLOCK, retrier(), reporter()),
                publisher,
                registry(closeIdle),
                healthState,
                configuredSources.stream().collect(java.util.stream.Collectors.toMap(
                        source -> source.sourceId(), ignored -> debounce)),
                executor);
    }

    private TransportRegistry registry(Runnable idleMaintenance) {
        FakeTransport transport = new FakeTransport();
        return new TransportRegistry(List.of(new TransportRegistry.Binding(
                "endpoint-one", transport, idleMaintenance, () -> { })));
    }

    private Retrier retrier() {
        return new Retrier(new RetryPolicy(
                1, Duration.ofMillis(1), 1.0d, Duration.ofMillis(1), false), ignored -> { });
    }

    private SyncDiagnosticReporter reporter() {
        return new SyncDiagnosticReporter(new CollectingDiagnosticSink(), CLOCK);
    }

    private RemoteFetchSource source(String id) {
        return new RemoteFetchSource(id, "endpoint-" + id, "/" + id, List.of("*"), List.of());
    }

    private RemoteObject object(String path, long size) {
        return new RemoteObject(path, size, NOW);
    }

    private static void waitUntil(BooleanCondition condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        assertThat(condition.matches()).isTrue();
    }

    @FunctionalInterface
    private interface BooleanCondition {
        boolean matches();
    }

    private static final class FakeTransport implements FileTransport {
        private final Map<String, List<RemoteObject>> objects;
        private final List<String> listCalls = new CopyOnWriteArrayList<>();
        private final List<String> listThreadNames = new CopyOnWriteArrayList<>();
        private CountDownLatch entered;
        private CountDownLatch release;
        private boolean blockedOnce;

        private FakeTransport() {
            this(Map.of());
        }

        private FakeTransport(Map<String, List<RemoteObject>> objects) {
            this.objects = objects;
        }

        private void blockList(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            listCalls.add(endpoint + ":" + remotePath);
            listThreadNames.add(Thread.currentThread().getName());
            if (entered != null && !blockedOnce) {
                blockedOnce = true;
                entered.countDown();
                await(release);
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

        private void await(CountDownLatch latch) {
            AsyncTestSupport.awaitOrFail(latch, "release of blocked remote listing");
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

    private static final class ManualScheduledExecutor extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final List<ManualScheduledFuture> tasks = new CopyOnWriteArrayList<>();
        private volatile boolean shutdown;

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualScheduledFuture future = new ManualScheduledFuture(command);
            tasks.add(future);
            return future;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command,
                long initialDelay,
                long period,
                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            schedule(command, 0, TimeUnit.NANOSECONDS);
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object> {
        private final Runnable command;
        private volatile boolean cancelled;
        private volatile boolean done;

        private ManualScheduledFuture(Runnable command) {
            this.command = command;
        }

        private void run() {
            if (!cancelled) {
                command.run();
            }
            done = true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }
    }
}
