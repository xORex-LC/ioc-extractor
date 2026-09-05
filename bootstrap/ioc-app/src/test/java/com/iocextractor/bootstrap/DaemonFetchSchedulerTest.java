package com.iocextractor.bootstrap;

import com.iocextractor.application.sync.RemoteFetchSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DaemonFetchSchedulerTest {

    @Test
    void runOnceTriggersPeriodicDetectionForEveryConfiguredSource() {
        RecordingDetection detection = new RecordingDetection();
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one"), source("two")), detection, Duration.ofHours(1));

        scheduler.runOnce();

        assertThat(detection.triggers)
                .containsExactly("one:" + RemoteFetchDetectionReason.PERIODIC,
                        "two:" + RemoteFetchDetectionReason.PERIODIC);
    }

    @Test
    void startTriggersStartupDetectionForEveryConfiguredSourceBeforeFirstInterval() {
        RecordingDetection detection = new RecordingDetection();
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one"), source("two")), detection, Duration.ofHours(1));

        scheduler.start();
        scheduler.stop();

        assertThat(detection.triggers)
                .containsExactly("one:" + RemoteFetchDetectionReason.STARTUP,
                        "two:" + RemoteFetchDetectionReason.STARTUP);
    }

    @Test
    void slowCycleDoesNotOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingDetection detection = new RecordingDetection(entered, release);
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")), detection, Duration.ofHours(1));

        try (var first = AsyncTestSupport.startWorker(
                "daemon-fetch-overlap", scheduler::runOnce, release::countDown)) {
            assertThat(AsyncTestSupport.await(entered))
                    .as("first fetch cycle should enter detection")
                    .isTrue();
            scheduler.runOnce();
        }

        assertThat(detection.triggers).containsExactly("one:" + RemoteFetchDetectionReason.PERIODIC);
    }

    @Test
    void lifecycleStartsBeforeExportAndStopsCleanly() {
        RecordingDetection detection = new RecordingDetection();
        DaemonFetchScheduler scheduler = new DaemonFetchScheduler(
                List.of(source("one")), detection, Duration.ofHours(1));

        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(scheduler.getPhase()).isLessThan(DaemonExportScheduler.PHASE);
        assertThat(detection.triggers).containsExactly("one:" + RemoteFetchDetectionReason.STARTUP);
        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
    }

    private RemoteFetchSource source(String id) {
        return new RemoteFetchSource(id, "endpoint-" + id, "/" + id, List.of("*"), List.of());
    }

    private static final class RecordingDetection implements RemoteFetchDetectionTrigger {
        private final List<String> triggers = new ArrayList<>();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private RecordingDetection() {
            this(null, null);
        }

        private RecordingDetection(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void trigger(RemoteFetchSource source, RemoteFetchDetectionReason reason) {
            triggers.add(source.sourceId() + ":" + reason);
            if (entered != null) {
                entered.countDown();
                AsyncTestSupport.awaitOrFail(release, "release of blocked fetch detection");
            }
        }
    }
}
