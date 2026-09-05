package com.iocextractor.bootstrap;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class AsyncTestSupport {

    static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);

    private AsyncTestSupport() {
    }

    static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static void awaitOrFail(CountDownLatch latch, String description) {
        try {
            if (!await(latch)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, interrupted);
        }
    }

    static Worker startWorker(String name, Runnable task, Runnable unblock) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().name(name).unstarted(() -> {
            try {
                task.run();
            } catch (RuntimeException | Error workerFailure) {
                failure.set(workerFailure);
            }
        });
        thread.start();
        return new Worker(thread, unblock, failure);
    }

    static void interruptAndAwaitTermination(Thread thread, String description) throws InterruptedException {
        thread.interrupt();
        awaitTermination(thread, description);
    }

    static void shutdownAndAwaitTermination(ExecutorService executor, String description)
            throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("executor should terminate after forced shutdown: %s", description)
                    .isTrue();
        }
    }

    private static void awaitTermination(Thread thread, String description) throws InterruptedException {
        thread.join(WAIT_TIMEOUT.toMillis());
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(WAIT_TIMEOUT.toMillis());
        }
        assertThat(thread.isAlive())
                .as("worker should terminate: %s", description)
                .isFalse();
    }

    static final class Worker implements AutoCloseable {
        private final Thread thread;
        private final Runnable unblock;
        private final AtomicReference<Throwable> failure;

        private Worker(Thread thread, Runnable unblock, AtomicReference<Throwable> failure) {
            this.thread = thread;
            this.unblock = unblock;
            this.failure = failure;
        }

        @Override
        public void close() throws InterruptedException {
            unblock.run();
            awaitTermination(thread, thread.getName());
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                throw new AssertionError("Worker failed: " + thread.getName(), workerFailure);
            }
        }
    }
}
