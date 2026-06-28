package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.export.SliceRetentionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonSliceRetentionSchedulerTest {

    @Test
    void lifecycleRunsAfterExportAndStopsCleanly() {
        var scheduler = scheduler(() -> result(0));

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(scheduler.getPhase()).isGreaterThan(DaemonExportScheduler.PHASE);

        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void overlapIsDroppedAndFailureCanBeRetried() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = scheduler(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                entered.countDown();
                await(release);
                throw new IllegalStateException("transient");
            }
            return result(1);
        });
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.runOnce();
        release.countDown();
        first.join(1000);
        scheduler.runOnce();

        assertThat(attempts).hasValue(2);
    }

    private DaemonSliceRetentionScheduler scheduler(
            com.iocextractor.application.port.in.export.RunSliceRetentionUseCase useCase) {
        return new DaemonSliceRetentionScheduler(
                useCase, Duration.ofHours(1), Duration.ofHours(1));
    }

    private SliceRetentionResult result(int deleted) {
        return new SliceRetentionResult(deleted, deleted, 0, Map.of("profile", deleted));
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
