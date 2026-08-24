package com.iocextractor.adapter.out.store.jdbc;

import com.iocextractor.common.IocExtractorException;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Fair same-process admission for canonical SQLite write ownership.
 *
 * <p>The database transaction remains the correctness boundary. This guard
 * prevents one local writer family from repeatedly barging ahead of another.
 */
public final class JdbcWriterAdmission {

    private final ReentrantLock lock = new ReentrantLock(true);

    /** Executes one already-prepared database write attempt in FIFO admission order. */
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IocExtractorException("Interrupted while waiting for canonical JDBC write admission", failure);
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    boolean fair() {
        return lock.isFair();
    }

    int queuedWriters() {
        return lock.getQueueLength();
    }
}
