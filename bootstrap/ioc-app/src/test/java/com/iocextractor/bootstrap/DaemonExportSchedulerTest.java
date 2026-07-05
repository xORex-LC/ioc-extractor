package com.iocextractor.bootstrap;

import com.iocextractor.application.cadence.CadenceSource;
import com.iocextractor.application.cadence.IntervalCadenceSource;
import com.iocextractor.application.cadence.QuietPeriodCadenceSource;
import com.iocextractor.application.export.ArtifactRevision;
import com.iocextractor.application.export.ExportArtifactSpec;
import com.iocextractor.application.export.ExportFormat;
import com.iocextractor.application.export.ExportMode;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProfile;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.export.ExportRunStatus;
import com.iocextractor.application.port.in.export.ExportArtifactsResult;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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

    @Test
    void startupNudgeSchedulesCheckThroughPolicyDelay() {
        ManualExecutor executor = new ManualExecutor();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), () -> executor);

        scheduler.start();

        assertThat(executor.fixedDelayTasks).hasSize(1);
        assertThat(executor.delayedTasks).singleElement()
                .extracting(task -> task.delay())
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void secondNudgeCoalescesWhileCheckIsAlreadyScheduled() {
        ManualExecutor executor = new ManualExecutor();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), () -> executor);
        scheduler.start();

        scheduler.nudge();

        assertThat(executor.delayedTasks).hasSize(1);
    }

    @Test
    void disabledPolicyMakesNudgeNoop() {
        ManualExecutor executor = new ManualExecutor();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                ExportNudgePolicy.disabled(), () -> executor);

        scheduler.start();
        scheduler.nudge();

        assertThat(executor.fixedDelayTasks).hasSize(1);
        assertThat(executor.delayedTasks).isEmpty();
    }

    @Test
    void stopAndLateNudgeAreNoop() {
        ManualExecutor executor = new ManualExecutor();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), () -> executor);
        scheduler.start();
        scheduler.stop();

        scheduler.nudge();

        assertThat(executor.delayedTasks).hasSize(1);
    }

    @Test
    void nudgeFlagDoesNotSurviveLifecycleRestart() {
        Queue<ManualExecutor> executors = new ArrayDeque<>();
        ManualExecutor first = new ManualExecutor();
        ManualExecutor second = new ManualExecutor();
        executors.add(first);
        executors.add(second);
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), executors::remove);

        scheduler.start();
        scheduler.stop();
        scheduler.start();
        second.runNextDelayed();
        scheduler.nudge();

        assertThat(first.delayedTasks).hasSize(1);
        assertThat(second.delayedTasks).hasSize(1);
    }

    @Test
    void pendingNotDueSchedulesFollowUpButIdleDoesNot() {
        ManualExecutor pendingExecutor = new ManualExecutor();
        ExportPlan plan = plan("one");
        var pendingScheduler = nudgedScheduler(
                List.of(plan), Map.of("one", neverDue()),
                profile -> List.of(progress(plan, plan.planHash())), artifacts -> nullRevision(artifacts),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), () -> pendingExecutor);
        pendingScheduler.start();

        pendingExecutor.runNextDelayed();

        assertThat(pendingExecutor.delayedTasks).hasSize(1);

        ManualExecutor idleExecutor = new ManualExecutor();
        var idleScheduler = nudgedScheduler(
                List.of(), Map.of(),
                profile -> List.of(), artifacts -> List.of(),
                () -> 0, command -> completed(command.profile()),
                Duration.ofSeconds(7), () -> idleExecutor);
        idleScheduler.start();

        idleExecutor.runNextDelayed();

        assertThat(idleExecutor.delayedTasks).isEmpty();
    }

    @Test
    void busyNudgedCheckSchedulesFollowUp() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return completed(command.profile());
                },
                Duration.ofSeconds(7), () -> executor);
        scheduler.start();
        Thread first = new Thread(scheduler::runOnce);

        first.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        executor.runNextDelayed();
        release.countDown();
        first.join(1000);

        assertThat(attempts).hasValue(1);
        assertThat(executor.delayedTasks).hasSize(1);
    }

    @Test
    void failedNudgedCheckDoesNotScheduleFollowUp() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = nudgedScheduler(
                List.of(plan("one")), Map.of("one", alwaysDue()),
                profile -> List.of(), artifacts -> nullRevision(artifacts),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("transient");
                },
                Duration.ofSeconds(7), () -> executor);
        scheduler.start();

        executor.runNextDelayed();

        assertThat(attempts).hasValue(1);
        assertThat(executor.delayedTasks).isEmpty();
    }

    @Test
    void quietPeriodMaxCapFiresThroughFollowUpChecks() {
        MutableClock clock = new MutableClock(START);
        Duration quietPeriod = Duration.ofSeconds(5);
        ExportPlan plan = plan("one");
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger attempts = new AtomicInteger();
        var scheduler = nudgedScheduler(
                List.of(plan),
                Map.of("one", new QuietPeriodCadenceSource(quietPeriod, Duration.ofSeconds(15), clock)),
                profile -> List.of(progress(plan, plan.planHash(), START.minusSeconds(1))),
                artifacts -> artifacts.stream()
                        .map(name -> new ArtifactRevision(name, 1, clock.instant()))
                        .toList(),
                () -> 0, command -> {
                    attempts.incrementAndGet();
                    return completed(command.profile());
                },
                quietPeriod, () -> executor);
        scheduler.start();

        executor.runNextDelayed();
        clock.advance(quietPeriod);
        executor.runNextDelayed();
        clock.advance(quietPeriod);
        executor.runNextDelayed();
        clock.advance(quietPeriod);
        executor.runNextDelayed();

        assertThat(attempts).hasValue(1);
        assertThat(executor.delayedTasks).isEmpty();
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
                artifacts -> nullRevision(artifacts),
                progressStore, recovery, exporter, Duration.ofHours(1));
    }

    private DaemonExportScheduler nudgedScheduler(
            List<ExportPlan> plans,
            Map<String, CadenceSource> cadences,
            ExportProgressStore progressStore,
            ArtifactRevisionReader revisionReader,
            com.iocextractor.application.port.in.export.RecoverExportUseCase recovery,
            com.iocextractor.application.port.in.export.ExportArtifactsUseCase exporter,
            Duration delay,
            Supplier<ScheduledExecutorService> executorFactory) {
        return nudgedScheduler(plans, cadences, progressStore, revisionReader, recovery, exporter,
                new ExportNudgePolicy(true, delay), executorFactory);
    }

    private DaemonExportScheduler nudgedScheduler(
            List<ExportPlan> plans,
            Map<String, CadenceSource> cadences,
            ExportProgressStore progressStore,
            ArtifactRevisionReader revisionReader,
            com.iocextractor.application.port.in.export.RecoverExportUseCase recovery,
            com.iocextractor.application.port.in.export.ExportArtifactsUseCase exporter,
            ExportNudgePolicy nudgePolicy,
            Supplier<ScheduledExecutorService> executorFactory) {
        return new DaemonExportScheduler(
                plans, cadences, revisionReader, progressStore, recovery, exporter,
                Duration.ofHours(1), nudgePolicy, executorFactory);
    }

    private List<ArtifactRevision> nullRevision(List<String> artifacts) {
        return artifacts.stream()
                .map(name -> new ArtifactRevision(name, 0, null))
                .toList();
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
        return progress(plan, planHash, START);
    }

    private ExportProgress progress(ExportPlan plan, String planHash, Instant updatedAt) {
        String artifact = plan.artifacts().getFirst().artifactName();
        return new ExportProgress(
                plan.profile().name(), artifact, 0, "c".repeat(64), "slice-1", planHash, updatedAt);
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

    private static final class ManualExecutor implements ScheduledExecutorService {
        private final Queue<ScheduledTask> delayedTasks = new ArrayDeque<>();
        private final List<Runnable> fixedDelayTasks = new ArrayList<>();
        private boolean shutdown;

        void runNextDelayed() {
            ScheduledTask task = delayedTasks.remove();
            task.command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("executor is stopped");
            }
            ScheduledTask task = new ScheduledTask(command, Duration.ofMillis(unit.toMillis(delay)));
            delayedTasks.add(task);
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("callable scheduling is not used");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                      long initialDelay,
                                                      long period,
                                                      TimeUnit unit) {
            throw new UnsupportedOperationException("fixed-rate scheduling is not used");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                         long initialDelay,
                                                         long delay,
                                                         TimeUnit unit) {
            if (shutdown) {
                throw new RejectedExecutionException("executor is stopped");
            }
            fixedDelayTasks.add(command);
            return new ScheduledTask(command, Duration.ofMillis(unit.toMillis(initialDelay)));
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException("submit is not used");
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException("submit is not used");
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException("submit is not used");
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException("invokeAll is not used");
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout,
                                             TimeUnit unit) {
            throw new UnsupportedOperationException("invokeAll is not used");
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException("invokeAny is not used");
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("invokeAny is not used");
        }
    }

    private record ScheduledTask(Runnable command, Duration delay) implements ScheduledFuture<Object> {

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delay);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
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
