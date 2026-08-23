package com.iocextractor.bootstrap;

import com.iocextractor.application.port.out.sync.FileTransport;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.port.out.sync.RemoteFetchLedger;
import com.iocextractor.application.sync.PublishAtomicallyRequest;
import com.iocextractor.application.sync.PublishReceipt;
import com.iocextractor.application.sync.RemoteFetchRecord;
import com.iocextractor.application.sync.RemoteFetchStatus;
import com.iocextractor.application.sync.RemoteFetchInFlightRegistry;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RemoteWatchTarget;
import com.iocextractor.application.sync.RemoteObject;
import com.iocextractor.application.sync.RemoteObjectIdentity;
import com.iocextractor.application.sync.RemoteSourceMonitor;
import com.iocextractor.application.sync.Retrier;
import com.iocextractor.application.sync.RetryPolicy;
import com.iocextractor.application.sync.SyncDiagnosticReporter;
import com.iocextractor.diagnostics.sink.CollectingDiagnosticSink;
import com.iocextractor.platform.events.RecordingControlEventPublisher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteChangeWatchLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private final RemoteFetchSource source = new RemoteFetchSource(
            "incoming", "primary", "/send", List.of("*"), List.of());

    @Test
    void startsEnabledWatchAndBridgesSignalToDetection() {
        FakeTransport transport = new FakeTransport();
        FakeSignalSource signals = new FakeSignalSource();
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(transport);
             TransportRegistry registry = new TransportRegistry(List.of(new TransportRegistry.Binding(
                     "primary", transport, transport::closeIdle, transport, signals)))) {
            RemoteChangeWatchLifecycle lifecycle = new RemoteChangeWatchLifecycle(
                    List.of(source), registry, coordinator);

            lifecycle.start();
            signals.handler.signal();

            waitUntil(() -> transport.listCalls.get() == 1);
            lifecycle.stop();

            assertThat(signals.target).isEqualTo(RemoteWatchTarget.from(source));
            assertThat(signals.closed).isTrue();
        }
    }

    @Test
    void failsFastWhenEnabledEndpointDoesNotExposeSignals() {
        FakeTransport transport = new FakeTransport();
        try (RemoteFetchDetectionCoordinator coordinator = coordinator(transport);
             TransportRegistry registry = new TransportRegistry(List.of(new TransportRegistry.Binding(
                     "primary", transport, transport::closeIdle, transport)))) {
            RemoteChangeWatchLifecycle lifecycle = new RemoteChangeWatchLifecycle(
                    List.of(source), registry, coordinator);

            assertThatThrownBy(lifecycle::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not expose remote change signals");
        }
    }

    private RemoteFetchDetectionCoordinator coordinator(FakeTransport transport) {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        return new RemoteFetchDetectionCoordinator(
                List.of(source),
                new RemoteSourceMonitor(transport, new FakeLedger(), new RemoteFetchInFlightRegistry(),
                        List.of(source), 10, CLOCK,
                        new Retrier(new RetryPolicy(
                                1, Duration.ofMillis(1), 1.0d, Duration.ofMillis(1), false), ignored -> { }),
                        new SyncDiagnosticReporter(new CollectingDiagnosticSink(), CLOCK)),
                new RecordingControlEventPublisher(),
                new TransportRegistry(List.of(new TransportRegistry.Binding(
                        "primary", transport, transport::closeIdle, transport))),
                new SyncHealthState(CLOCK),
                java.util.Map.of(source.sourceId(), Duration.ofMillis(10)),
                executor);
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

    private static final class FakeSignalSource implements RemoteChangeSignalSource {
        private RemoteWatchTarget target;
        private RemoteChangeSignalHandler handler;
        private boolean closed;

        @Override
        public RemoteChangeWatch watch(RemoteWatchTarget target, RemoteChangeSignalHandler handler) {
            this.target = target;
            this.handler = handler;
            return () -> closed = true;
        }
    }

    private static final class FakeTransport implements FileTransport, AutoCloseable {
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicInteger idleCalls = new AtomicInteger();

        @Override
        public List<RemoteObject> list(String endpoint, String remotePath) {
            listCalls.incrementAndGet();
            return List.of();
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
            return new PublishReceipt(request.remotePath(), "ok");
        }

        private void closeIdle() {
            idleCalls.incrementAndGet();
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeLedger implements RemoteFetchLedger {

        @Override
        public Optional<RemoteFetchRecord> find(RemoteObjectIdentity identity) {
            return Optional.empty();
        }

        @Override
        public RemoteFetchRecord markFetched(RemoteObjectIdentity identity, String localPath, Instant fetchedAt) {
            return new RemoteFetchRecord(identity, RemoteFetchStatus.FETCHED, localPath, 1, null, fetchedAt, fetchedAt);
        }

        @Override
        public RemoteFetchRecord markSkipped(RemoteObjectIdentity identity, String reason, Instant skippedAt) {
            return new RemoteFetchRecord(identity, RemoteFetchStatus.SKIPPED, null, 0, reason, null, skippedAt);
        }

        @Override
        public RemoteFetchRecord markFailed(RemoteObjectIdentity identity, String reason, Instant failedAt) {
            return new RemoteFetchRecord(identity, RemoteFetchStatus.FAILED, null, 1, reason, null, failedAt);
        }
    }
}
