package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PeriodicDaemonCycleTest {

    @Test
    void runOnceDoesNotOverlapSlowCycle() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PeriodicDaemonCycle cycle = new PeriodicDaemonCycle("test-cycle", Duration.ofHours(1), () -> {
            calls.incrementAndGet();
            entered.countDown();
            AsyncTestSupport.awaitOrFail(release, "release of blocked periodic cycle");
        });

        try (var first = AsyncTestSupport.startWorker(
                "periodic-cycle-overlap", cycle::runOnce, release::countDown)) {
            assertThat(AsyncTestSupport.await(entered))
                    .as("first periodic cycle should enter its work")
                    .isTrue();
            cycle.runOnce();
        }

        assertThat(calls).hasValue(1);
    }

    @Test
    void startAndStopControlLifecycleState() {
        PeriodicDaemonCycle cycle = new PeriodicDaemonCycle("test-cycle", Duration.ofHours(1), () -> { });

        cycle.start();
        assertThat(cycle.isRunning()).isTrue();

        cycle.stop();
        assertThat(cycle.isRunning()).isFalse();
    }

    @Test
    void unexpectedFailureDoesNotDisableNextCycle() {
        AtomicInteger calls = new AtomicInteger();
        PeriodicDaemonCycle cycle = new PeriodicDaemonCycle("test-cycle", Duration.ofHours(1), () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
        });

        cycle.runOnce();
        cycle.runOnce();

        assertThat(calls).hasValue(2);
    }
}
