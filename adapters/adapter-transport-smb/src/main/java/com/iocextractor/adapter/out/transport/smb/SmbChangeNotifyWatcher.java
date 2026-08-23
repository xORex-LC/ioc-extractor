package com.iocextractor.adapter.out.transport.smb;

import com.iocextractor.application.port.out.sync.RemoteChangeSignalHandler;
import com.iocextractor.application.port.out.sync.RemoteChangeSignalSource;
import com.iocextractor.application.port.out.sync.RemoteChangeWatch;
import com.iocextractor.application.sync.RemoteWatchTarget;
import com.iocextractor.application.sync.RetryPolicy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SMB2 CHANGE_NOTIFY signal source.
 *
 * <p>The watcher is a doorbell only: it never lists or fetches files. A signal asks the
 * bootstrap coordinator to run the normal remote-source detection path.
 */
public final class SmbChangeNotifyWatcher implements RemoteChangeSignalSource {

    static final Duration DEFAULT_MAX_SESSION_AGE = Duration.ofMinutes(30);
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
    static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, SmbEndpointSettings> endpoints;
    private final RetryPolicy retryPolicy;
    private final SmbChangeNotifySessionFactory sessionFactory;
    private final Duration maxSessionAge;
    private final Duration pollInterval;
    private final Duration closeTimeout;

    /** Creates a production watcher over configured SMB endpoints. */
    public SmbChangeNotifyWatcher(List<SmbEndpointSettings> endpoints, RetryPolicy retryPolicy) {
        this(endpoints, retryPolicy, new SmbjChangeNotifySessionFactory(),
                DEFAULT_MAX_SESSION_AGE, DEFAULT_POLL_INTERVAL, DEFAULT_CLOSE_TIMEOUT);
    }

    SmbChangeNotifyWatcher(List<SmbEndpointSettings> endpoints,
                           RetryPolicy retryPolicy,
                           SmbChangeNotifySessionFactory sessionFactory,
                           Duration maxSessionAge,
                           Duration pollInterval,
                           Duration closeTimeout) {
        Objects.requireNonNull(endpoints, "endpoints");
        Map<String, SmbEndpointSettings> indexed = new HashMap<>();
        for (SmbEndpointSettings endpoint : endpoints) {
            SmbEndpointSettings previous = indexed.putIfAbsent(endpoint.name(), endpoint);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate SMB endpoint: " + endpoint.name());
            }
        }
        this.endpoints = Map.copyOf(indexed);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.maxSessionAge = requirePositive(maxSessionAge, "maxSessionAge");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.closeTimeout = requirePositive(closeTimeout, "closeTimeout");
    }

    @Override
    public RemoteChangeWatch watch(RemoteWatchTarget target, RemoteChangeSignalHandler handler) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(handler, "handler");
        SmbEndpointSettings endpoint = endpoints.get(target.endpoint());
        if (endpoint == null) {
            throw new IllegalArgumentException("unknown SMB endpoint: " + target.endpoint());
        }
        WatchWorker worker = new WatchWorker(endpoint, target, handler);
        worker.start();
        return worker;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private final class WatchWorker implements RemoteChangeWatch, Runnable {
        private final SmbEndpointSettings endpoint;
        private final RemoteWatchTarget target;
        private final RemoteChangeSignalHandler handler;
        private final Thread thread;
        private volatile boolean closed;
        private volatile SmbChangeNotifySession currentSession;
        private volatile SmbChangeNotifyPending currentPending;

        private WatchWorker(SmbEndpointSettings endpoint,
                            RemoteWatchTarget target,
                            RemoteChangeSignalHandler handler) {
            this.endpoint = endpoint;
            this.target = target;
            this.handler = handler;
            this.thread = new Thread(this, "ioc-smb-watch-" + target.sourceId());
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        @Override
        public void run() {
            int failedAttempts = 0;
            while (!closed) {
                try {
                    runSession();
                    failedAttempts = 0;
                } catch (RuntimeException failure) {
                    if (closed) {
                        return;
                    }
                    failedAttempts++;
                    handler.failed(failure);
                    sleep(backoff(failedAttempts));
                }
            }
        }

        private void runSession() {
            try (SmbChangeNotifySession session = sessionFactory.open(endpoint, target.remotePath())) {
                currentSession = session;
                handler.established();
                long sessionDeadline = System.nanoTime() + maxSessionAge.toNanos();
                while (!closed && System.nanoTime() < sessionDeadline) {
                    SmbChangeNotifyPending pending = session.watch();
                    currentPending = pending;
                    Optional<SmbChangeNotifyResult> result = awaitPendingOrLeaseDeadline(pending, sessionDeadline);
                    currentPending = null;
                    if (closed) {
                        pending.cancel();
                        return;
                    }
                    if (result.isEmpty()) {
                        pending.cancel();
                        return;
                    }
                    if (result.get().shouldSignal()) {
                        handler.signal();
                    }
                }
            } finally {
                currentPending = null;
                currentSession = null;
            }
        }

        private Optional<SmbChangeNotifyResult> awaitPendingOrLeaseDeadline(
                SmbChangeNotifyPending pending,
                long sessionDeadline) {
            while (!closed && System.nanoTime() < sessionDeadline) {
                long remainingNanos = sessionDeadline - System.nanoTime();
                Duration timeout = Duration.ofNanos(Math.min(pollInterval.toNanos(), Math.max(1L, remainingNanos)));
                Optional<SmbChangeNotifyResult> result = pending.await(timeout);
                if (result.isPresent()) {
                    return result;
                }
                if (Thread.currentThread().isInterrupted()) {
                    closed = true;
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }

        private Duration backoff(int failedAttempts) {
            double scaled = retryPolicy.backoff().toNanos() * Math.pow(retryPolicy.multiplier(), failedAttempts - 1.0d);
            long nanos = scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled);
            Duration capped = Duration.ofNanos(nanos).compareTo(retryPolicy.maxBackoff()) > 0
                    ? retryPolicy.maxBackoff()
                    : Duration.ofNanos(nanos);
            if (!retryPolicy.jitter() || capped.toNanos() <= 1L) {
                return capped;
            }
            return Duration.ofNanos(ThreadLocalRandom.current().nextLong(1L, capped.toNanos() + 1L));
        }

        private void sleep(Duration delay) {
            try {
                Thread.sleep(delay.toMillis(), delay.toNanosPart() % 1_000_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                closed = true;
            }
        }

        @Override
        public void close() {
            closed = true;
            SmbChangeNotifyPending pending = currentPending;
            if (pending != null) {
                pending.cancel();
            }
            SmbChangeNotifySession session = currentSession;
            if (session != null) {
                session.close();
            }
            thread.interrupt();
            try {
                thread.join(closeTimeout.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing SMB change notify watch", interrupted);
            }
            if (thread.isAlive()) {
                throw new IllegalStateException("Timed out while closing SMB change notify watch for " + target.sourceId());
            }
        }
    }
}
