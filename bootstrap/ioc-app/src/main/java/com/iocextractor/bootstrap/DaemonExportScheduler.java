package com.iocextractor.bootstrap;

import com.iocextractor.application.cadence.CadenceSource;
import com.iocextractor.application.artifact.lifecycle.CanonicalDataAdmissionState;
import com.iocextractor.application.artifact.lifecycle.EffectiveTime;
import com.iocextractor.application.export.ExportPlan;
import com.iocextractor.application.export.ExportProgress;
import com.iocextractor.application.port.in.export.ExportArtifactsCommand;
import com.iocextractor.application.port.in.export.ExportArtifactsUseCase;
import com.iocextractor.application.port.in.export.RecoverExportUseCase;
import com.iocextractor.application.port.out.export.ArtifactRevisionReader;
import com.iocextractor.application.port.out.export.ExportProgressStore;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Daemon cadence boundary with independent, bounded per-profile execution.
 *
 * <p>Recovery runs synchronously when the common canonical-data admission gate opens, before the
 * first scheduled poll. Two workers share a queue bounded by the configured profile catalog.
 * At most one queued/running attempt exists per profile; cadence checks and formation run on
 * workers so a blocked profile cannot hold the detection timer. Durable per-profile single-flight
 * and profile file leases remain authoritative across processes.
 */
public final class DaemonExportScheduler implements SmartLifecycle, ExportNudgeTrigger {

    /** Starts after ordinary lifecycle components; retention uses a later phase. */
    public static final int PHASE = 100;

    private static final Logger log = LoggerFactory.getLogger(DaemonExportScheduler.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final List<ExportPlan> plans;
    private final Map<String, CadenceSource> cadences;
    private final ArtifactRevisionReader revisionReader;
    private final ExportProgressStore progressStore;
    private final RecoverExportUseCase recovery;
    private final ExportArtifactsUseCase exporter;
    private final Duration pollInterval;
    private final ExportNudgePolicy nudgePolicy;
    private final Supplier<ScheduledExecutorService> executorFactory;
    private final Supplier<ExecutorService> workerFactory;
    private final Set<String> inFlight = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final CanonicalDataAdmissionState admission;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean nudgeScheduled = new AtomicBoolean();

    private volatile boolean active;
    private volatile ScheduledExecutorService executor;
    private volatile ExecutorService workers;

    /** Creates a scheduler with one cadence source per configured profile. */
    public DaemonExportScheduler(List<ExportPlan> plans,
                                 Map<String, CadenceSource> cadences,
                                 ArtifactRevisionReader revisionReader,
                                 ExportProgressStore progressStore,
                                 RecoverExportUseCase recovery,
                                 ExportArtifactsUseCase exporter,
                                 Duration pollInterval) {
        this(plans, cadences, revisionReader, progressStore, recovery, exporter,
                pollInterval, ExportNudgePolicy.disabled());
    }

    /** Creates a scheduler with one cadence source per configured profile and optional nudges. */
    public DaemonExportScheduler(List<ExportPlan> plans,
                                 Map<String, CadenceSource> cadences,
                                 ArtifactRevisionReader revisionReader,
                                 ExportProgressStore progressStore,
                                 RecoverExportUseCase recovery,
                                 ExportArtifactsUseCase exporter,
                                 Duration pollInterval,
                                 ExportNudgePolicy nudgePolicy) {
        this(plans, cadences, revisionReader, progressStore, recovery, exporter,
                pollInterval, nudgePolicy,
                CanonicalDataAdmissionState.admittedCompatible(EffectiveTime.at(Instant.EPOCH)),
                DaemonExportScheduler::newExecutor);
    }

    /** Creates an export scheduler that remains inert until canonical admission. */
    public DaemonExportScheduler(List<ExportPlan> plans,
                                 Map<String, CadenceSource> cadences,
                                 ArtifactRevisionReader revisionReader,
                                 ExportProgressStore progressStore,
                                 RecoverExportUseCase recovery,
                                 ExportArtifactsUseCase exporter,
                                 Duration pollInterval,
                                 ExportNudgePolicy nudgePolicy,
                                 CanonicalDataAdmissionState admission) {
        this(plans, cadences, revisionReader, progressStore, recovery, exporter,
                pollInterval, nudgePolicy, admission, DaemonExportScheduler::newExecutor);
    }

    DaemonExportScheduler(List<ExportPlan> plans,
                          Map<String, CadenceSource> cadences,
                          ArtifactRevisionReader revisionReader,
                          ExportProgressStore progressStore,
                          RecoverExportUseCase recovery,
                          ExportArtifactsUseCase exporter,
                          Duration pollInterval,
                          ExportNudgePolicy nudgePolicy,
                          Supplier<ScheduledExecutorService> executorFactory) {
        this(plans, cadences, revisionReader, progressStore, recovery, exporter,
                pollInterval, nudgePolicy,
                CanonicalDataAdmissionState.admittedCompatible(EffectiveTime.at(Instant.EPOCH)),
                executorFactory);
    }

    DaemonExportScheduler(List<ExportPlan> plans,
                          Map<String, CadenceSource> cadences,
                          ArtifactRevisionReader revisionReader,
                          ExportProgressStore progressStore,
                          RecoverExportUseCase recovery,
                          ExportArtifactsUseCase exporter,
                          Duration pollInterval,
                          ExportNudgePolicy nudgePolicy,
                          CanonicalDataAdmissionState admission,
                          Supplier<ScheduledExecutorService> executorFactory) {
        this(plans, cadences, revisionReader, progressStore, recovery, exporter, pollInterval,
                nudgePolicy, admission, executorFactory, () -> newWorkers(plans.size()));
    }

    DaemonExportScheduler(List<ExportPlan> plans,
                          Map<String, CadenceSource> cadences,
                          ArtifactRevisionReader revisionReader,
                          ExportProgressStore progressStore,
                          RecoverExportUseCase recovery,
                          ExportArtifactsUseCase exporter,
                          Duration pollInterval,
                          ExportNudgePolicy nudgePolicy,
                          CanonicalDataAdmissionState admission,
                          Supplier<ScheduledExecutorService> executorFactory,
                          Supplier<ExecutorService> workerFactory) {
        this.plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        this.cadences = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(cadences, "cadences")));
        this.revisionReader = Objects.requireNonNull(revisionReader, "revisionReader");
        this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.pollInterval = requirePositive(pollInterval);
        this.nudgePolicy = Objects.requireNonNull(nudgePolicy, "nudgePolicy");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.admission = Objects.requireNonNull(admission, "admission");
        List<String> profiles = this.plans.stream().map(plan -> plan.profile().name()).toList();
        if (!this.cadences.keySet().containsAll(profiles) || this.cadences.size() != profiles.size()) {
            throw new IllegalArgumentException("Cadence sources must match configured export profiles");
        }
    }

    @Override
    public synchronized void start() {
        if (active) {
            return;
        }
        if (workers != null || executor != null) {
            throw new IllegalStateException("Previous export executors have not terminated");
        }
        nudgeScheduled.set(false);
        active = true;
        admission.whenAdmitted(this::openAfterAdmission);
    }

    private synchronized void openAfterAdmission() {
        if (!active || executor != null) {
            return;
        }
        recovery.recoverIncomplete();
        workers = Objects.requireNonNull(workerFactory.get(), "workers");
        executor = Objects.requireNonNull(executorFactory.get(), "executor");
        executor.scheduleWithFixedDelay(
                this::runOnce, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        nudge();
    }

    /** Dispatches a coalesced profile check; later polls recover failed or rejected hints. */
    public void runOnce() {
        runProfiles();
    }

    @Override
    public void nudge() {
        if (!active || !nudgePolicy.enabled()) {
            return;
        }
        ScheduledExecutorService current = executor;
        if (current == null || !nudgeScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            current.schedule(this::runNudgedCheck, nudgePolicy.delay().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            nudgeScheduled.set(false);
        }
    }

    private void runNudgedCheck() {
        nudgeScheduled.set(false);
        SchedulerOutcome outcome = runProfiles();
        if (outcome == SchedulerOutcome.PENDING_NOT_DUE || outcome == SchedulerOutcome.BUSY) {
            nudge();
        }
    }

    private SchedulerOutcome runProfiles() {
        if (!active || workers == null) {
            return SchedulerOutcome.IDLE;
        }
        if (!running.compareAndSet(false, true)) {
            return SchedulerOutcome.BUSY;
        }
        SchedulerOutcome outcome = SchedulerOutcome.IDLE;
        try {
            for (ExportPlan plan : plans) {
                outcome = outcome.merge(dispatch(plan));
            }
        } finally {
            running.set(false);
        }
        return outcome;
    }

    private SchedulerOutcome dispatch(ExportPlan plan) {
        String profile = plan.profile().name();
        if (!inFlight.add(profile)) {
            return SchedulerOutcome.BUSY;
        }
        ExecutorService current = workers;
        if (current == null) {
            inFlight.remove(profile);
            return SchedulerOutcome.IDLE;
        }
        try {
            current.execute(() -> {
                SchedulerOutcome outcome;
                try {
                    outcome = attempt(plan);
                } finally {
                    inFlight.remove(profile);
                }
                if (outcome == SchedulerOutcome.PENDING_NOT_DUE) {
                    nudge();
                }
            });
            return SchedulerOutcome.ATTEMPTED;
        } catch (RejectedExecutionException rejected) {
            inFlight.remove(profile);
            return SchedulerOutcome.BUSY;
        }
    }

    /** Bounded queued/running attempts; this excludes remote publication work. */
    public int outstandingProfiles() {
        return inFlight.size();
    }

    private SchedulerOutcome attempt(ExportPlan plan) {
        String profile = plan.profile().name();
        CadenceSource cadence = cadences.get(profile);
        try {
            List<ExportProgress> progress = progressStore.findByProfile(profile);
            if (coversCurrentPlan(plan, progress)) {
                List<String> artifacts = plan.artifacts().stream()
                        .map(spec -> spec.artifactName()).toList();
                Instant activity = revisionReader.read(artifacts).stream()
                        .map(revision -> revision.changedAt())
                        .filter(Objects::nonNull)
                        .max((left, right) -> left.compareTo(right))
                        .orElse(null);
                Instant checkpoint = progress.stream()
                        .map(item -> item.updatedAt())
                        .max((left, right) -> left.compareTo(right))
                        .orElse(null);
                if (nudgePolicy.enabled() && !hasPendingActivity(activity, checkpoint)) {
                    return SchedulerOutcome.IDLE;
                }
                if (!cadence.isDue(activity, checkpoint)) {
                    return SchedulerOutcome.PENDING_NOT_DUE;
                }
            }
            exporter.export(new ExportArtifactsCommand(profile));
            cadence.completed();
            return SchedulerOutcome.ATTEMPTED;
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.EXPORT_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_EXPORT_PROFILE, profile)
                    .message("scheduled artifact export attempt failed")
                    .log(failure);
            return SchedulerOutcome.FAILED;
        }
    }

    /**
     * Returns whether every configured artifact has progress for the exact current plan.
     *
     * <p>Missing progress and plan drift must reach the export use case independently of
     * canonical activity. This covers initial export after upgrading an existing database and
     * deterministic re-emission after byte-affecting configuration changes.
     */
    private boolean coversCurrentPlan(ExportPlan plan, List<ExportProgress> progress) {
        if (progress.size() != plan.artifacts().size()) {
            return false;
        }
        Map<String, ExportProgress> byArtifact = new LinkedHashMap<>();
        for (ExportProgress item : progress) {
            if (byArtifact.put(item.artifactName(), item) != null) {
                return false;
            }
        }
        String planHash = plan.planHash();
        return plan.artifacts().stream().allMatch(spec -> {
            ExportProgress item = byArtifact.get(spec.artifactName());
            return item != null && item.planHash().equals(planHash);
        });
    }

    private boolean hasPendingActivity(Instant activity, Instant checkpoint) {
        return activity != null && (checkpoint == null || activity.isAfter(checkpoint));
    }

    @Override
    public synchronized void stop() {
        active = false;
        terminate(executor);
        executor = null;
        terminate(workers);
        workers = null;
        inFlight.clear();
    }

    private void terminate(ExecutorService owned) {
        if (owned == null) {
            return;
        }
        owned.shutdown();
        try {
            if (!owned.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                owned.shutdownNow();
                if (!owned.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("Export workers did not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            owned.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping export workers", interrupted);
        }
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "pollInterval");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        return value;
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-export-scheduler");
            thread.setDaemon(false);
            return thread;
        });
    }

    private static ExecutorService newWorkers(int profiles) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, profiles)),
                Thread.ofPlatform().name("ioc-export-profile-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private enum SchedulerOutcome {
        IDLE,
        FAILED,
        ATTEMPTED,
        PENDING_NOT_DUE,
        BUSY;

        // PENDING/BUSY keep the nudge loop alive even when another profile failed;
        // failures alone still wait for the periodic poll backstop.
        private SchedulerOutcome merge(SchedulerOutcome other) {
            return priority() >= other.priority() ? this : other;
        }

        private int priority() {
            return switch (this) {
                case IDLE -> 0;
                case FAILED -> 1;
                case ATTEMPTED -> 2;
                case PENDING_NOT_DUE -> 3;
                case BUSY -> 4;
            };
        }
    }
}
