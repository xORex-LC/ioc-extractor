package com.iocextractor.platform.concurrent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynchronousKeyedExecutionGuardTest {

    @Test
    void serializesSameKeyAndReportsWaitingCaller() throws Exception {
        var guard = new SynchronousKeyedExecutionGuard();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> guard.execute(WorkKey.of("same"), () -> {
                firstEntered.countDown();
                await(releaseFirst);
                return "first";
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> guard.execute(WorkKey.of("same"), () -> {
                secondEntered.countDown();
                return "second";
            }));
            awaitWaitingCaller(guard);

            assertThat(secondEntered.getCount()).isOne();
            assertThat(guard.snapshot())
                    .isEqualTo(new KeyedExecutionGuardSnapshot(1, 1, 1));

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
        } finally {
            releaseFirst.countDown();
        }

        assertThat(guard.snapshot()).isEqualTo(KeyedExecutionGuardSnapshot.empty());
    }

    @Test
    void allowsDifferentKeysToExecuteConcurrently() throws Exception {
        var guard = new SynchronousKeyedExecutionGuard();
        var bothEntered = new CountDownLatch(2);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> guard.execute(WorkKey.of("first"), () -> {
                bothEntered.countDown();
                await(release);
                return null;
            }));
            var second = executor.submit(() -> guard.execute(WorkKey.of("second"), () -> {
                bothEntered.countDown();
                await(release);
                return null;
            }));

            assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(guard.snapshot())
                    .isEqualTo(new KeyedExecutionGuardSnapshot(2, 2, 0));

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void propagatesFailureAndRemovesIdleState() {
        var guard = new SynchronousKeyedExecutionGuard();

        assertThatThrownBy(() -> guard.execute(WorkKey.of("failed"), () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(guard.snapshot()).isEqualTo(KeyedExecutionGuardSnapshot.empty());
    }

    @Test
    void preservesWorkFailureWhenReleaseInvariantAlsoFails() {
        var guard = new SynchronousKeyedExecutionGuard();
        var key = WorkKey.of("corrupted-release");
        var workFailure = new IllegalArgumentException("work failed");

        assertThatThrownBy(() -> guard.execute(key, () -> {
            removeTrackedState(guard, key);
            throw workFailure;
        }))
                .isSameAs(workFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement()
                        .satisfies(suppressed -> assertThat(suppressed)
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("Keyed execution state changed while in use")));

        assertThat(guard.snapshot()).isEqualTo(KeyedExecutionGuardSnapshot.empty());
    }

    @Test
    void reportsReleaseInvariantWhenWorkSucceeds() {
        var guard = new SynchronousKeyedExecutionGuard();
        var key = WorkKey.of("corrupted-successful-release");

        assertThatThrownBy(() -> guard.execute(key, () -> {
            removeTrackedState(guard, key);
            return "completed";
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Keyed execution state changed while in use")
                .hasNoSuppressedExceptions();

        assertThat(guard.snapshot()).isEqualTo(KeyedExecutionGuardSnapshot.empty());
    }

    private static void awaitWaitingCaller(SynchronousKeyedExecutionGuard guard) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (guard.snapshot().waiting() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(guard.snapshot().waiting())
                .as("a same-key caller is waiting at the guard")
                .isOne();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test coordination interrupted", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeTrackedState(SynchronousKeyedExecutionGuard guard, WorkKey key) {
        try {
            Field statesField = SynchronousKeyedExecutionGuard.class.getDeclaredField("states");
            statesField.setAccessible(true);
            var states = (Map<WorkKey, Object>) statesField.get(guard);
            states.remove(key);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not create the release-invariant test condition", failure);
        }
    }
}
