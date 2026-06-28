package com.iocextractor.application.sync;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Small framework-free executor for micro-retries of transport operations.
 */
public final class Retrier {

    private final RetryPolicy policy;
    private final RetrySleeper sleeper;

    /** Creates a retrier using the production thread sleeper. */
    public Retrier(RetryPolicy policy) {
        this(policy, RetrySleeper.threadSleep());
    }

    /** Creates a retrier with an explicit sleeper for deterministic tests/adapters. */
    public Retrier(RetryPolicy policy, RetrySleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** Executes an operation, retrying only transport failures with {@code RETRY_NOW}. */
    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (RemoteTransportException failure) {
                if (!shouldRetry(failure, attempt)) {
                    throw failure;
                }
                waitBeforeRetry(attempt);
                attempt++;
            }
        }
    }

    /** Executes a void operation with the same retry rules as {@link #execute(Supplier)}. */
    public void run(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        execute(() -> {
            operation.run();
            return null;
        });
    }

    private boolean shouldRetry(RemoteTransportException failure, int attempt) {
        return failure.kind().disposition() == RemoteErrorDisposition.RETRY_NOW
                && attempt < policy.maxAttempts();
    }

    private void waitBeforeRetry(int failedAttempt) {
        try {
            sleeper.sleep(jitter(policy.delayAfterAttempt(failedAttempt)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry remote operation", interrupted);
        }
    }

    private Duration jitter(Duration delay) {
        if (!policy.jitter()) {
            return delay;
        }
        long nanos = delay.toNanos();
        if (nanos <= 1L) {
            return delay;
        }
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(1L, nanos + 1L));
    }
}
