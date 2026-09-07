package com.iocextractor.platform.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class BoundedKeyedSerialExecutorTest {

    private ExecutorService workers;
    private BoundedKeyedSerialExecutor keyedExecutor;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        releases.forEach(CountDownLatch::countDown);
        try {
            if (keyedExecutor != null) {
                keyedExecutor.shutdown();
                assertThat(keyedExecutor.awaitTermination(Duration.ofSeconds(5))).isTrue();
            }
        } finally {
            if (workers != null) {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private CountDownLatch releaseLatch() {
        var latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    @Test
    void doesNotOverlapWorkForSameKey() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(2, 8);
        WorkKey key = WorkKey.of("endpoint-a");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = releaseLatch();
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
        CountDownLatch release = releaseLatch();
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
        CountDownLatch releaseFirst = releaseLatch();
        CountDownLatch acceptedOtherKey = new CountDownLatch(1);
        CountDownLatch sameKeyFinished = new CountDownLatch(2);

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> blockingSignal(
                firstStarted, releaseFirst, sameKeyFinished)).accepted()).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(WorkKey.of("endpoint-a"), sameKeyFinished::countDown).accepted()).isTrue();

        WorkAdmission rejected = executor.submit(WorkKey.of("endpoint-a"), () -> { });
        WorkAdmission otherKey = executor.submit(WorkKey.of("endpoint-b"), acceptedOtherKey::countDown);

        assertThat(rejected.status()).isEqualTo(WorkAdmissionStatus.REJECTED);
        assertThat(rejected.queuedDepth()).isEqualTo(1);
        assertThat(observer.rejections).containsExactly(rejected);
        assertThat(otherKey.accepted()).isTrue();
        assertThat(acceptedOtherKey.await(1, TimeUnit.SECONDS)).isTrue();
        releaseFirst.countDown();
        assertThat(sameKeyFinished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shutdownDrainsAcceptedWorkAndRejectsNewWork() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(1, 8);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = releaseLatch();
        CountDownLatch finished = new CountDownLatch(2);

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> blockingSignal(
                firstStarted, releaseFirst, finished)).accepted()).isTrue();
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(WorkKey.of("endpoint-a"), finished::countDown).accepted()).isTrue();

        executor.shutdown();

        assertThat(executor.submit(WorkKey.of("endpoint-a"), () -> { }).status())
                .isEqualTo(WorkAdmissionStatus.REJECTED);
        try {
            releaseFirst.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.awaitTermination(Duration.ofSeconds(1))).isTrue();
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void observesUnhandledWorkFailureAndContinuesKey() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        BoundedKeyedSerialExecutor executor = executor(1, 8, observer);
        WorkKey key = WorkKey.of("endpoint-a");
        RuntimeException failure = new IllegalStateException("work failed");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = releaseLatch();
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
    void observesNormalCompletionForRecoveredKey() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        BoundedKeyedSerialExecutor executor = executor(1, 8, observer);
        WorkKey key = WorkKey.of("endpoint-a");

        executor.submit(key, () -> { });

        assertThat(observer.completionObserved.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(observer.completions).containsExactly(key);
    }

    @Test
    void snapshotsRunningKeysQueueDepthAndOldestAge() throws InterruptedException {
        BoundedKeyedSerialExecutor executor = executor(1, 8);
        WorkKey key = WorkKey.of("endpoint-a");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = releaseLatch();
        CountDownLatch finished = new CountDownLatch(2);

        executor.submit(key, () -> blockingSignal(firstStarted, releaseFirst, finished));
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        executor.submit(key, finished::countDown);

        KeyedSerialExecutorSnapshot snapshot = executor.snapshot();

        assertThat(snapshot.keys()).singleElement().satisfies(lane -> {
            assertThat(lane.key()).isEqualTo(key);
            assertThat(lane.running()).isTrue();
            assertThat(lane.queuedDepth()).isEqualTo(1);
            assertThat(lane.oldestAge()).isGreaterThanOrEqualTo(Duration.ZERO);
        });
        releaseFirst.countDown();
        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void abandonsAndObservesQueuedWorkWhenWorkerRejectsDuringDrain() throws InterruptedException {
        RecordingObserver observer = new RecordingObserver();
        workers = new RejectingAfterFirstExecuteExecutorService();
        keyedExecutor = new BoundedKeyedSerialExecutor(workers, 8, observer);
        try (BoundedKeyedSerialExecutor executor = keyedExecutor) {
            WorkKey key = WorkKey.of("endpoint-a");
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = releaseLatch();
            CountDownLatch firstFinished = new CountDownLatch(1);

            assertThat(executor.submit(key, () -> blockingSignal(
                    firstStarted, releaseFirst, firstFinished)).accepted()).isTrue();
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.submit(key, () -> { }).accepted()).isTrue();
            assertThat(executor.submit(key, () -> { }).accepted()).isTrue();

            releaseFirst.countDown();

            assertThat(observer.dispatchRejectedObserved.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(firstFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observer.dispatchRejections).singleElement()
                    .satisfies(rejection -> {
                        assertThat(rejection.key()).isEqualTo(key);
                        assertThat(rejection.abandonedWork()).isEqualTo(2);
                        assertThat(rejection.failure()).isInstanceOf(RejectedExecutionException.class);
                    });
        }
    }

    @Test
    void oldestAgeIncludesQueueWaitAfterNextTaskStarts() throws InterruptedException {
        var clock = new MutableClock();
        workers = Executors.newSingleThreadExecutor();
        keyedExecutor = new BoundedKeyedSerialExecutor(
                workers, 1, NoopKeyedSerialExecutorObserver.INSTANCE, clock);
        var key = WorkKey.of("age");
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseFirst = releaseLatch();
        var releaseSecond = releaseLatch();
        keyedExecutor.submit(key, () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        clock.now.set(Instant.EPOCH.plusSeconds(1));
        assertThat(keyedExecutor.submit(key, () -> {
            secondStarted.countDown();
            await(releaseSecond);
        }).accepted()).isTrue();

        clock.now.set(Instant.EPOCH.plusSeconds(61));
        assertThat(keyedExecutor.snapshot().keys()).singleElement()
                .extracting(KeyedWorkSnapshot::oldestAge).isEqualTo(Duration.ofSeconds(61));
        releaseFirst.countDown();
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(keyedExecutor.snapshot().keys()).singleElement().satisfies(lane -> {
            assertThat(lane.oldestAge()).isEqualTo(Duration.ofSeconds(60));
            assertThat(lane.queuedDepth()).isZero();
            assertThat(lane.running()).isTrue();
        });
    }

    @Test
    void zeroQueueBoundStillAdmitsOneDispatchedTaskPerKey() throws InterruptedException {
        var executor = executor(1, 0);
        var firstStarted = new CountDownLatch(1);
        var release = releaseLatch();
        var secondFinished = new CountDownLatch(1);
        var firstKey = WorkKey.of("a");
        var secondKey = WorkKey.of("b");
        assertThat(executor.submit(firstKey, () -> {
            firstStarted.countDown();
            await(release);
        }).accepted()).isTrue();
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(executor.submit(firstKey, () -> { }).accepted()).isFalse();
        assertThat(executor.submit(secondKey, secondFinished::countDown).accepted()).isTrue();
        assertThat(executor.submit(secondKey, () -> { }).accepted()).isFalse();
        assertThat(executor.snapshot().keys()).hasSize(2).allSatisfy(lane -> {
            assertThat(lane.running()).isTrue();
            assertThat(lane.queuedDepth()).isZero();
        });
        assertThat(secondFinished.getCount()).isOne();
        release.countDown();
        assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void firstDispatchRejectionRemovesLaneAndReportsOnlyDispatchFailure() {
        var observer = new RecordingObserver();
        var executor = executor(1, 1, observer);
        // Fault injection: an unavailable backing pool must explicitly reject execution.
        workers.shutdown();
        var key = WorkKey.of("rejected");

        assertThat(executor.submit(key, () -> {
            throw new AssertionError("rejected work must not run");
        }).accepted()).isFalse();

        assertThat(executor.snapshot().keys()).isEmpty();
        assertThat(observer.rejections).isEmpty();
        assertThat(observer.dispatchRejections).singleElement().satisfies(rejection -> {
            assertThat(rejection.key()).isEqualTo(key);
            assertThat(rejection.abandonedWork()).isOne();
        });
    }

    @Test
    void observerRuntimeFailuresDoNotPreventProgressOrRejection() throws InterruptedException {
        var observer = new ThrowingObserver();
        var executor = executor(1, 1, observer);
        var key = WorkKey.of("observer");
        var firstStarted = new CountDownLatch(1);
        var release = releaseLatch();
        var secondFinished = new CountDownLatch(1);
        executor.submit(key, () -> {
            firstStarted.countDown();
            await(release);
            throw new IllegalStateException("work failed");
        });
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(key, secondFinished::countDown).accepted()).isTrue();
        assertThat(executor.submit(key, () -> { }).accepted()).isFalse();
        release.countDown();
        assertThat(secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(Duration.ofSeconds(5))).isTrue();
        assertThat(observer.calls).containsExactly("rejected", "failed", "completed");
    }

    @Test
    void dispatchObserverRuntimeFailureDoesNotPreventRejectionOrShutdown() {
        var observer = new ThrowingObserver();
        var executor = executor(1, 1, observer);
        workers.shutdown();

        assertThat(executor.submit(WorkKey.of("rejected"), () -> { }).accepted()).isFalse();
        assertThat(executor.snapshot().keys()).isEmpty();
        assertThat(observer.calls).containsExactly("dispatchRejected");
    }

    private BoundedKeyedSerialExecutor executor(int workerCount, int maxQueuedPerKey) {
        return executor(workerCount, maxQueuedPerKey, NoopKeyedSerialExecutorObserver.INSTANCE);
    }

    private BoundedKeyedSerialExecutor executor(int workerCount,
                                               int maxQueuedPerKey,
                                               KeyedSerialExecutorObserver observer) {
        workers = Executors.newFixedThreadPool(workerCount);
        keyedExecutor = new BoundedKeyedSerialExecutor(workers, maxQueuedPerKey, observer);
        return keyedExecutor;
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

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class ThrowingObserver implements KeyedSerialExecutorObserver {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void completed(WorkKey key) {
            fail("completed");
        }

        @Override
        public void rejected(WorkAdmission admission) {
            fail("rejected");
        }

        @Override
        public void failed(WorkKey key, RuntimeException failure) {
            fail("failed");
        }

        @Override
        public void dispatchRejected(WorkKey key, int abandonedWork, RejectedExecutionException failure) {
            fail("dispatchRejected");
        }

        private void fail(String callback) {
            calls.add(callback);
            throw new IllegalStateException("observer failed");
        }
    }

    private static final class RecordingObserver implements KeyedSerialExecutorObserver {
        private final CountDownLatch completionObserved = new CountDownLatch(1);
        private final CountDownLatch failureObserved = new CountDownLatch(1);
        private final CountDownLatch dispatchRejectedObserved = new CountDownLatch(1);
        private final List<WorkAdmission> rejections = new CopyOnWriteArrayList<>();
        private final List<WorkKey> completions = new CopyOnWriteArrayList<>();
        private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        private final List<DispatchRejection> dispatchRejections = new CopyOnWriteArrayList<>();

        @Override
        public void completed(WorkKey key) {
            completions.add(key);
            completionObserved.countDown();
        }

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
