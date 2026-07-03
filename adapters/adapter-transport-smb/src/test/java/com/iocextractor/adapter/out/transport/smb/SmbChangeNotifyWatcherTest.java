package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteFetchSource;
import com.iocextractor.application.sync.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SmbChangeNotifyWatcherTest {

    @Test
    void signalsAndRearmsAfterCompletedNotify() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        FakeSession session = waitForSession(factory, 0);
        FakePending first = waitForPending(session, 0);
        first.complete(new SmbChangeNotifyResult(1, false));

        waitUntil(() -> handler.signals.get() == 1 && session.watchCalls.get() >= 2);
        watch.close();

        assertThat(handler.established).hasValue(1);
        assertThat(session.closed).isTrue();
    }

    @Test
    void overflowNotifySignalsAndRearms() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        FakeSession session = waitForSession(factory, 0);
        FakePending first = waitForPending(session, 0);
        first.complete(new SmbChangeNotifyResult(0, true));

        waitUntil(() -> handler.signals.get() == 1 && session.watchCalls.get() >= 2);
        watch.close();

        assertThat(handler.established).hasValue(1);
        assertThat(handler.failures).hasValue(0);
    }

    @Test
    void emptyNotifyDoesNotSignalButRearms() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        FakeSession session = waitForSession(factory, 0);
        FakePending first = waitForPending(session, 0);
        first.complete(new SmbChangeNotifyResult(0, false));

        waitUntil(() -> session.watchCalls.get() >= 2);
        watch.close();

        assertThat(handler.signals).hasValue(0);
        assertThat(handler.failures).hasValue(0);
    }

    @Test
    void leaseReopenCallsEstablishedWithoutSignal() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofMillis(40));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);

        waitUntil(() -> handler.established.get() >= 2 && factory.sessions.size() >= 2);
        watch.close();

        assertThat(handler.signals).hasValue(0);
        assertThat(factory.sessions).hasSizeGreaterThanOrEqualTo(2);
        assertThat(factory.sessions.getFirst().pendings.getFirst().cancelled).isTrue();
    }

    @Test
    void reconnectsAfterOpenFailureAndReportsFailure() {
        FakeSessionFactory factory = new FakeSessionFactory();
        factory.failures.add(new RuntimeException("connect denied"));
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);

        waitUntil(() -> handler.failures.get() == 1 && handler.established.get() == 1);
        watch.close();

        assertThat(factory.sessions).hasSize(1);
        assertThat(handler.lastFailure).hasMessageContaining("connect denied");
    }

    @Test
    void reconnectsAfterPendingFailureAndReportsFailure() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        FakeSession firstSession = waitForSession(factory, 0);
        FakePending first = waitForPending(firstSession, 0);
        first.fail(new RuntimeException("delete pending"));

        waitUntil(() -> handler.failures.get() == 1 && handler.established.get() == 2);
        watch.close();

        assertThat(handler.signals).hasValue(0);
        assertThat(handler.lastFailure).hasMessageContaining("delete pending");
        assertThat(factory.sessions).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void workerThreadIsDaemon() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        waitForSession(factory, 0);
        watch.close();

        assertThat(factory.lastOpenThreadDaemon).isTrue();
    }

    @Test
    void closeCancelsPendingWatch() {
        FakeSessionFactory factory = new FakeSessionFactory();
        SmbChangeNotifyWatcher watcher = watcher(factory, Duration.ofSeconds(5));
        RecordingHandler handler = new RecordingHandler();

        RemoteChangeWatch watch = watcher.watch(source(), handler);
        FakeSession session = waitForSession(factory, 0);
        FakePending pending = waitForPending(session, 0);

        watch.close();

        assertThat(pending.cancelled).isTrue();
        assertThat(session.closed).isTrue();
    }

    private static SmbChangeNotifyWatcher watcher(FakeSessionFactory factory, Duration maxSessionAge) {
        return new SmbChangeNotifyWatcher(
                List.of(settings()),
                new RetryPolicy(3, Duration.ofMillis(5), 1.0d, Duration.ofMillis(5), false),
                factory,
                maxSessionAge,
                Duration.ofMillis(5),
                Duration.ofSeconds(1));
    }

    private static SmbEndpointSettings settings() {
        return new SmbEndpointSettings(
                "primary",
                "files.example.test",
                "share",
                "",
                "sync-user",
                "secret".toCharArray(),
                false,
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5));
    }

    private static RemoteFetchSource source() {
        return new RemoteFetchSource("incoming", "primary", "/send", List.of("*"), List.of());
    }

    private static FakeSession waitForSession(FakeSessionFactory factory, int index) {
        waitUntil(() -> factory.sessions.size() > index);
        return factory.sessions.get(index);
    }

    private static FakePending waitForPending(FakeSession session, int index) {
        waitUntil(() -> session.pendings.size() > index);
        return session.pendings.get(index);
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

    private static final class FakeSessionFactory implements SmbChangeNotifySessionFactory {
        private final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
        private final Queue<RuntimeException> failures = new ArrayDeque<>();
        private volatile boolean lastOpenThreadDaemon;

        @Override
        public synchronized SmbChangeNotifySession open(SmbEndpointSettings settings, String remotePath) {
            lastOpenThreadDaemon = Thread.currentThread().isDaemon();
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }
    }

    private static final class FakeSession implements SmbChangeNotifySession {
        private final List<FakePending> pendings = new CopyOnWriteArrayList<>();
        private final AtomicInteger watchCalls = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public synchronized SmbChangeNotifyPending watch() {
            watchCalls.incrementAndGet();
            FakePending pending = new FakePending();
            pendings.add(pending);
            return pending;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakePending implements SmbChangeNotifyPending {
        private boolean done;
        private boolean cancelled;
        private SmbChangeNotifyResult result = new SmbChangeNotifyResult(0, false);
        private RuntimeException failure;

        @Override
        public synchronized Optional<SmbChangeNotifyResult> await(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!done) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return Optional.empty();
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return Optional.of(result);
        }

        @Override
        public synchronized boolean cancel() {
            cancelled = true;
            done = true;
            notifyAll();
            return true;
        }

        private synchronized void complete(SmbChangeNotifyResult result) {
            this.result = result;
            done = true;
            notifyAll();
        }

        private synchronized void fail(RuntimeException failure) {
            this.failure = failure;
            done = true;
            notifyAll();
        }
    }

    private static final class RecordingHandler implements RemoteChangeSignalHandler {
        private final AtomicInteger signals = new AtomicInteger();
        private final AtomicInteger established = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private volatile RuntimeException lastFailure;

        @Override
        public void signal() {
            signals.incrementAndGet();
        }

        @Override
        public void established() {
            established.incrementAndGet();
        }

        @Override
        public void failed(RuntimeException failure) {
            lastFailure = failure;
            failures.incrementAndGet();
        }
    }
}
