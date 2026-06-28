package com.iocextractor.bootstrap;

import com.iocextractor.application.cadence.CadenceSource;
import com.iocextractor.application.export.ArtifactRevision;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daemon lifecycle boundary that samples durable cadence facts and invokes profiles sequentially.
 *
 * <p>Recovery is synchronous in {@link #start()}, before the first scheduled poll. One executor
 * and an explicit overlap guard ensure this process never starts two formation attempts at once;
 * the service-database single-flight remains authoritative across processes.
 */
public final class DaemonExportScheduler implements SmartLifecycle {

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
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile boolean active;
    private ScheduledExecutorService executor;

    /** Creates a scheduler with one cadence source per configured profile. */
    public DaemonExportScheduler(List<ExportPlan> plans,
                                 Map<String, CadenceSource> cadences,
                                 ArtifactRevisionReader revisionReader,
                                 ExportProgressStore progressStore,
                                 RecoverExportUseCase recovery,
                                 ExportArtifactsUseCase exporter,
                                 Duration pollInterval) {
        this.plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        this.cadences = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(cadences, "cadences")));
        this.revisionReader = Objects.requireNonNull(revisionReader, "revisionReader");
        this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.pollInterval = requirePositive(pollInterval);
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
        recovery.recoverIncomplete();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ioc-export-scheduler");
            thread.setDaemon(false);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::runOnce, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        active = true;
    }

    /** Executes one non-overlapping poll; profile failures are isolated and retried on later polls. */
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (ExportPlan plan : plans) {
                attempt(plan);
            }
        } finally {
            running.set(false);
        }
    }

    private void attempt(ExportPlan plan) {
        String profile = plan.profile().name();
        CadenceSource cadence = cadences.get(profile);
        try {
            List<String> artifacts = plan.artifacts().stream()
                    .map(spec -> spec.artifactName()).toList();
            Instant activity = revisionReader.read(artifacts).stream()
                    .map(ArtifactRevision::changedAt)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);
            Instant checkpoint = progressStore.findByProfile(profile).stream()
                    .map(ExportProgress::updatedAt)
                    .max(Instant::compareTo)
                    .orElse(null);
            if (!cadence.isDue(activity, checkpoint)) {
                return;
            }
            exporter.export(new ExportArtifactsCommand(profile));
            cadence.completed();
        } catch (RuntimeException failure) {
            LogEvents.error(log)
                    .action(EventAction.EXPORT_COMPLETE)
                    .outcome(EventOutcome.FAILURE)
                    .field(LogField.IOC_EXPORT_PROFILE, profile)
                    .message("scheduled artifact export attempt failed")
                    .log(failure);
        }
    }

    @Override
    public synchronized void stop() {
        active = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
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
}
