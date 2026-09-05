package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.export.SliceRetentionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
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
                AsyncTestSupport.awaitOrFail(release, "release of blocked retention cycle");
                throw new IllegalStateException("transient");
            }
            return result(1);
        });

        try (var first = AsyncTestSupport.startWorker(
                "slice-retention-overlap", scheduler::runOnce, release::countDown)) {
            assertThat(AsyncTestSupport.await(entered))
                    .as("first retention cycle should enter the use case")
                    .isTrue();
            scheduler.runOnce();
        }
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
}
