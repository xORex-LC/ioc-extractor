package com.iocextractor.bootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodicDaemonCycleTest {

    @Test
    void runOnceDoesNotOverlapSlowCycle() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PeriodicDaemonCycle cycle = new PeriodicDaemonCycle("test-cycle", Duration.ofHours(1), () -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
        });
        Thread first = new Thread(cycle::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        cycle.runOnce();
        release.countDown();
        first.join(1000);

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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
