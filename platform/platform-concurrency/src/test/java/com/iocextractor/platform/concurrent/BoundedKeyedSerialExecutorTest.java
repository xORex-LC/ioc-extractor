package com.iocextractor.platform.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedKeyedSerialExecutorTest {

    private ExecutorService workers;

    @AfterEach
    void tearDown() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    @Test
    void doesNotOverlapWorkForSameKey() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(2, 8);
        WorkKey key = WorkKey.of("endpoint-a");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        assertThat(executor.submit(key, () -> trackedBlockingWork(
                running, maxRunning, firstStarted, releaseFirst, finished))).extracting(WorkAdmission::accepted)
                .isEqualTo(true);
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(key, () -> trackedWork(running, maxRunning, finished)).accepted()).isTrue();

        releaseFirst.countDown();

        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(maxRunning).hasValue(1);
    }

    @Test
    void keepsFifoOrderWithinOneKey() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(2, 8);
        WorkKey key = WorkKey.of("endpoint-a");
        CountDownLatch finished = new CountDownLatch(3);
        List<Integer> seen = new CopyOnWriteArrayList<>();

        executor.submit(key, () -> record(1, seen, finished));
        executor.submit(key, () -> record(2, seen, finished));
        executor.submit(key, () -> record(3, seen, finished));

        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly(1, 2, 3);
    }

    @Test
    void runsDifferentKeysConcurrently() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(2, 8);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);

        executor.submit(WorkKey.of("endpoint-a"), () -> blockingSignal(bothStarted, release, finished));
        executor.submit(WorkKey.of("endpoint-b"), () -> blockingSignal(bothStarted, release, finished));

        assertThat(bothStarted.await(1, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void rejectsOverflowForSaturatedKeyWithoutBlockingOtherKeys() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        BoundedKeyedSerialExecutor executor = executor(2, 1, observer);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch acceptedOtherKey = new CountDownLatch(1);

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> blockingSignal(
                firstStarted, releaseFirst, new CountDownLatch(0))).accepted()).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> { }).accepted()).isTrue();

        WorkAdmission rejected = executor.submit(WorkKey.of("endpoint-a"), () -> { });
        WorkAdmission otherKey = executor.submit(WorkKey.of("endpoint-b"), acceptedOtherKey::countDown);

        assertThat(rejected.status()).isEqualTo(WorkAdmissionStatus.REJECTED);
        assertThat(rejected.queuedDepth()).isEqualTo(1);
        assertThat(observer.rejections).containsExactly(rejected);
        assertThat(otherKey.accepted()).isTrue();
        assertThat(acceptedOtherKey.await(1, TimeUnit.SECONDS)).isTrue();
        releaseFirst.countDown();
    }

    @Test
    void shutdownDrainsAcceptedWorkAndRejectsNewWork() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(1, 8);
        CountDownLatch finished = new CountDownLatch(2);

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> finished.countDown()).accepted()).isTrue();
        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> finished.countDown()).accepted()).isTrue();

        executor.shutdown();

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> { }).status())
                .isEqualTo(WorkAdmissionStatus.REJECTED);
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.awaitTermination(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void observesUnhandledWorkFailureAndContinuesKey() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        BoundedKeyedSerialExecutor executor = executor(1, 8, observer);
        WorkKey key = WorkKey.of("endpoint-a");
        RuntimeException failure = new IllegalStateException("work failed");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        executor.submit(key, () -> {
            firstStarted.countDown();
            await(releaseFirst);
            throw failure;
        });
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(key, secondFinished::countDown).accepted()).isTrue();

        releaseFirst.countDown();

        assertThat(observer.failureObserved.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(secondFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(observer.failures).containsExactly(failure);
    }

    @Test
    void abandonsAndObservesQueuedWorkWhenWorkerRejectsDuringDrain() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        workers = new RejectingAfterFirstExecuteExecutorService();
        BoundedKeyedSerialExecutor executor = new BoundedKeyedSerialExecutor(workers, 8, observer);
        WorkKey key = WorkKey.of("endpoint-a");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        assertThat(executor.submit(key, () -> blockingSignal(
                firstStarted, releaseFirst, new CountDownLatch(0))).accepted()).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(key, () -> { }).accepted()).isTrue();
        assertThat(executor.submit(key, () -> { }).accepted()).isTrue();

        releaseFirst.countDown();

        assertThat(observer.dispatchRejectedObserved.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(observer.dispatchRejections).singleElement()
                .satisfies(rejection -> {
                    assertThat(rejection.key()).isEqualTo(key);
                    assertThat(rejection.abandonedWork()).isEqualTo(2);
                    assertThat(rejection.failure()).isInstanceOf(RejectedExecutionException.class);
                });
    }

    private BoundedKeyedSerialExecutor executor(int workerCount, int maxQueuedPerKey) {
        return executor(workerCount, maxQueuedPerKey, NoopKeyedSerialExecutorObserver.INSTANCE);
    }

    private BoundedKeyedSerialExecutor executor(int workerCount,
                                               int maxQueuedPerKey,
                                               KeyedSerialExecutorObserver observer) {
        workers = Executors.newFixedThreadPool(workerCount);
        return new BoundedKeyedSerialExecutor(workers, maxQueuedPerKey, observer);
    }

    private void trackedBlockingWork(AtomicInteger running,
                                     AtomicInteger maxRunning,
                                     CountDownLatch started,
                                     CountDownLatch release,
                                     CountDownLatch finished) {
        tracked(running, maxRunning, () -> {
            started.countDown();
            await(release);
        });
        finished.countDown();
    }

    private void trackedWork(AtomicInteger running, AtomicInteger maxRunning, CountDownLatch finished) {
        tracked(running, maxRunning, () -> { });
        finished.countDown();
    }

    private void tracked(AtomicInteger running, AtomicInteger maxRunning, Runnable work) {
        int current = running.incrementAndGet();
        maxRunning.accumulateAndGet(current, Math::max);
        try {
            work.run();
        } finally {
            running.decrementAndGet();
        }
    }

    private void record(int value, List<Integer> seen, CountDownLatch finished) {
        seen.add(value);
        finished.countDown();
    }

    private void blockingSignal(CountDownLatch started, CountDownLatch release, CountDownLatch finished) {
        started.countDown();
        await(release);
        finished.countDown();
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class RecordingObserver implements KeyedSerialExecutorObserver {
        private final CountDownLatch failureObserved = new CountDownLatch(1);
        private final CountDownLatch dispatchRejectedObserved = new CountDownLatch(1);
        private final List<WorkAdmission> rejections = new CopyOnWriteArrayList<>();
        private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        private final List<DispatchRejection> dispatchRejections = new CopyOnWriteArrayList<>();

        @Override
        public void rejected(WorkAdmission admission) {
            rejections.add(admission);
        }

        @Override
        public void failed(WorkKey key, RuntimeException failure) {
            failures.add(failure);
            failureObserved.countDown();
        }

        @Override
        public void dispatchRejected(WorkKey key, int abandonedWork, RejectedExecutionException failure) {
            dispatchRejections.add(new DispatchRejection(key, abandonedWork, failure));
            dispatchRejectedObserved.countDown();
        }
    }

    private record DispatchRejection(WorkKey key,
                                     int abandonedWork,
                                     RejectedExecutionException failure) {
    }

    private static final class RejectingAfterFirstExecuteExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            if (executions.incrementAndGet() > 1) {
                throw new RejectedExecutionException("dispatch rejected");
            }
            delegate.execute(command);
        }
    }
}
