package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Non-preemptive operation admission; aging prevents starvation at transaction boundaries. */
public final class JdbcWriterAdmission {
    public enum OperationClass { CONTROL, EXPIRY, EXPORT_SLOTS, PROMOTION, MAINTENANCE }
    public record Metrics(long completed, long totalWaitNanos, long maximumWaitNanos,
                          long totalHoldNanos, long maximumHoldNanos) { }

    private static final long AGING_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final List<Request> waiting = new ArrayList<>();
    private final Map<OperationClass, Metrics> metrics = new EnumMap<>(OperationClass.class);
    private final java.util.function.LongSupplier nanos;
    private volatile Thread owner;
    private long sequence;

    public JdbcWriterAdmission() { this(System::nanoTime); }

    JdbcWriterAdmission(java.util.function.LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    /** Existing promotion callers retain FIFO order within their operation class. */
    public <T> T execute(Supplier<T> work) { return execute(OperationClass.PROMOTION, work); }

    public <T> T execute(OperationClass operation, Supplier<T> work) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(work, "work");
        // A nested control update belongs to the already-held atomic writer unit.
        if (owner == Thread.currentThread()) { return work.get(); }
        Request request = enter(operation);
        long started = nanos.getAsLong();
        try { return work.get(); }
        finally { leave(request, started); }
    }

    private Request enter(OperationClass operation) {
        Request request = null;
        try {
            lock.lockInterruptibly();
            try {
                request = new Request(operation, sequence++, nanos.getAsLong());
                waiting.add(request);
                while (owner != null || select() != request) { changed.await(); }
                waiting.remove(request);
                owner = Thread.currentThread();
                return request;
            } catch (InterruptedException interrupted) {
                waiting.remove(request);
                changed.signalAll();
                throw interrupted;
            } finally { lock.unlock(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Interrupted while waiting for canonical JDBC write admission", interrupted);
        }
    }

    private Request select() {
        long now = nanos.getAsLong();
        return waiting.stream().min(Comparator
                .comparingInt((Request request) -> now - request.enqueued() >= AGING_NANOS
                        ? -1 : request.operation().ordinal())
                .thenComparingLong(Request::sequence)).orElse(null);
    }

    private void leave(Request request, long started) {
        long hold = nanos.getAsLong() - started;
        long wait = started - request.enqueued();
        lock.lock();
        try {
            Metrics previous = metrics.getOrDefault(request.operation(), new Metrics(0, 0, 0, 0, 0));
            metrics.put(request.operation(), new Metrics(previous.completed() + 1,
                    previous.totalWaitNanos() + wait, Math.max(previous.maximumWaitNanos(), wait),
                    previous.totalHoldNanos() + hold, Math.max(previous.maximumHoldNanos(), hold)));
            owner = null;
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Aggregate operation telemetry; no individual IOC is logged or written to a ledger. */
    public Map<OperationClass, Metrics> snapshot() {
        lock.lock();
        try { return Map.copyOf(metrics); }
        finally { lock.unlock(); }
    }

    boolean fair() { return lock.isFair(); }
    public int queuedWriters() {
        lock.lock();
        try { return waiting.size(); }
        finally { lock.unlock(); }
    }
    private record Request(OperationClass operation, long sequence, long enqueued) { }
}
