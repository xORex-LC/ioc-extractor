package com.iocextractor.bootstrap;

import com.iocextractor.application.cadence.CadenceSource;
import com.iocextractor.application.cadence.IntervalCadenceSource;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DaemonExportSchedulerTest {

    private static final Instant START = Instant.parse("2026-06-28T00:00:00Z");

    @Test
    void startCompletesRecoveryBeforeSchedulingAndStopIsControlled() {
        List<String> calls = new ArrayList<>();
        var scheduler = scheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                () -> {
                    calls.add("recover");
                    return 0;
                }, command -> {
                    calls.add("export");
                    return completed(command.profile());
                });

        scheduler.start();
        try {
            assertThat(calls).containsExactly("recover");
            assertThat(scheduler.isRunning()).isTrue();
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void dueProfilesRunSequentiallyInConfigurationOrder() {
        MutableClock clock = new MutableClock(START);
        List<ExportPlan> plans = List.of(plan("one"), plan("two"));
        Map<String, CadenceSource> cadences = new LinkedHashMap<>();
        cadences.put("one", new IntervalCadenceSource(Duration.ofMinutes(1), clock));
        cadences.put("two", new IntervalCadenceSource(Duration.ofMinutes(1), clock));
        List<String> calls = new ArrayList<>();
        var scheduler = scheduler(plans, cadences, () -> 0, command -> {
            calls.add(command.profile());
            return completed(command.profile());
        });

        clock.advance(Duration.ofMinutes(1));
        scheduler.runOnce();

        assertThat(calls).containsExactly("one", "two");
    }

    @Test
    void overlappingPollIsDroppedWhileSlowRunIsActive() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = scheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()), () -> 0, command -> {
                    attempts.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return completed(command.profile());
                });
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.runOnce();
        release.countDown();
        first.join(1000);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void failedAttemptDoesNotKillSchedulerAndRetriesNextPoll() {
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = scheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()), () -> 0, command -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("transient");
                    }
                    return completed(command.profile());
                });

        scheduler.runOnce();
        scheduler.runOnce();

        assertThat(attempts).hasValue(2);
    }

    @Test
    void missingProgressBypassesCadenceForInitialExportWithoutCanonicalActivity() {
        AtomicInteger attempts = new AtomicInteger();
        ExportPlan plan = plan("one");
        var scheduler = scheduler(
                List.of(plan), Map.of("one", neverDue()), profile -> List.of(),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    return completed(command.profile());
                });

        scheduler.runOnce();

        assertThat(attempts).hasValue(1);
    }

    @Test
    void stalePlanProgressBypassesCadenceForDeterministicReEmission() {
        AtomicInteger attempts = new AtomicInteger();
        ExportPlan plan = plan("one");
        ExportProgress stale = progress(plan, "b".repeat(64));
        var scheduler = scheduler(
                List.of(plan), Map.of("one", neverDue()), profile -> List.of(stale),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    return completed(command.profile());
                });

        scheduler.runOnce();

        assertThat(attempts).hasValue(1);
    }

    @Test
    void matchingPlanProgressStillHonorsCadence() {
        AtomicInteger attempts = new AtomicInteger();
        ExportPlan plan = plan("one");
        ExportProgress current = progress(plan, plan.planHash());
        var scheduler = scheduler(
                List.of(plan), Map.of("one", neverDue()), profile -> List.of(current),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    return completed(command.profile());
                });

        scheduler.runOnce();

        assertThat(attempts).hasValue(0);
    }

    private DaemonExportScheduler scheduler(
            List<ExportPlan> plans,
            Map<String, CadenceSource> cadences,
            com.iocextractor.application.port.in.export.RecoverExportUseCase recovery,
            com.iocextractor.application.port.in.export.ExportArtifactsUseCase exporter) {
        return scheduler(plans, cadences, profile -> List.of(), recovery, exporter);
    }

    private DaemonExportScheduler scheduler(
            List<ExportPlan> plans,
            Map<String, CadenceSource> cadences,
            ExportProgressStore progressStore,
            com.iocextractor.application.port.in.export.RecoverExportUseCase recovery,
            com.iocextractor.application.port.in.export.ExportArtifactsUseCase exporter) {
        return new DaemonExportScheduler(
                plans, cadences,
                artifacts -> artifacts.stream()
                        .map(name -> new com.iocextractor.application.export.ArtifactRevision(name, 0, null))
                        .toList(),
                progressStore, recovery, exporter, Duration.ofHours(1));
    }

    private ExportPlan plan(String profile) {
        String hash = "a".repeat(64);
        return new ExportPlan(1,
                new ExportProfile(profile, ExportMode.COMPLETE, List.of(profile)),
                new ExportFormat("csv", "UTF-8", ";", "\"", "NULL"),
                List.of(new ExportArtifactSpec(
                        profile, profile + ".csv", List.of("id"), 1, hash, hash, hash)));
    }

    private CadenceSource alwaysDue() {
        return new CadenceSource() {
            @Override
            public boolean isDue(Instant lastActivity, Instant lastCheckpoint) {
                return true;
            }

            @Override
            public void completed() {
            }
        };
    }

    private CadenceSource neverDue() {
        return new CadenceSource() {
            @Override
            public boolean isDue(Instant lastActivity, Instant lastCheckpoint) {
                return false;
            }

            @Override
            public void completed() {
            }
        };
    }

    private ExportProgress progress(ExportPlan plan, String planHash) {
        String artifact = plan.artifacts().getFirst().artifactName();
        return new ExportProgress(
                plan.profile().name(), artifact, 0, "c".repeat(64), "slice-1", planHash, START);
    }

    private ExportArtifactsResult completed(String profile) {
        return new ExportArtifactsResult(
                "run-1", profile, ExportRunStatus.COMPLETED, "slice-1");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
