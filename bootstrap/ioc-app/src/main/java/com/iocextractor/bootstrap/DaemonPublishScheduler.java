package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import com.iocextractor.platform.concurrent.KeyedSerialExecutor;
import com.iocextractor.platform.concurrent.WorkAdmission;
import com.iocextractor.platform.concurrent.WorkKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Daemon lifecycle boundary for restart-safe publish reconciliation and delivery attempts.
 *
 * <p>Startup reconciliation is synchronous and performs no remote writes. Phase ordering places
 * this scheduler after export formation and before delivery-aware slice retention.
 */
public final class DaemonPublishScheduler implements SmartLifecycle {

    public static final int PHASE = 150;

    private static final Logger log = LoggerFactory.getLogger(DaemonPublishScheduler.class);

    private final List<PublishTarget> targets;
    private final ArtifactPublishUseCase publisher;
    private final TransportRegistry transports;
    private final SyncHealthState healthState;
    private final KeyedSerialExecutor executor;
    private final PeriodicDaemonCycle cycle;

    /** Creates one sequential publish scheduler over the configured target order. */
    public DaemonPublishScheduler(List<PublishTarget> targets,
                                  ArtifactPublishUseCase publisher,
                                  TransportRegistry transports,
                                  SyncHealthState healthState,
                                  KeyedSerialExecutor executor,
                                  Duration interval) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.healthState = Objects.requireNonNull(healthState, "healthState");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cycle = new PeriodicDaemonCycle("ioc-sync-publish-scheduler", interval, this::runCycle);
    }

    @Override
    public void start() {
        if (cycle.isRunning()) {
            return;
        }
        reconcileBeforeScheduling();
        cycle.start();
    }

    private void reconcileBeforeScheduling() {
        reconcileProfiles("startup publish reconciliation failed");
    }

    private void reconcileProfiles(String failureMessage) {
        for (Map.Entry<String, List<PublishTarget>> entry : targetsByProfile().entrySet()) {
            try {
                publisher.reconcile(reconcileCommand(entry.getKey()));
            } catch (RuntimeException failure) {
                for (PublishTarget target : entry.getValue()) {
                    logFailure(target, failureMessage, failure);
                }
            }
        }
    }

    /** Executes one non-overlapping publish cycle and isolates failures by target. */
    public void runOnce() {
        cycle.runOnce();
    }

    private void runCycle() {
        try {
            reconcileProfiles("scheduled remote publish reconciliation failed");
            for (PublishTarget target : targets) {
                attempt(target);
            }
        } finally {
            try {
                transports.closeIdle();
            } catch (RuntimeException failure) {
                LogEvents.warn(log)
                        .action(EventAction.MAINTENANCE)
                        .outcome(EventOutcome.FAILURE)
                        .message("sync transport idle cleanup failed")
                        .log(failure);
            }
        }
    }

    private void attempt(PublishTarget target) {
        CountDownLatch completed = new CountDownLatch(1);
        WorkAdmission admission = executor.submit(WorkKey.of(target.endpoint()), () -> {
            try {
                attemptOnExecutor(target);
            } finally {
                completed.countDown();
            }
        });
        if (!admission.accepted()) {
            logFailure(target, "scheduled remote publish target rejected", new IllegalStateException(
                    "scheduled publish work rejected for endpoint " + target.endpoint()));
            return;
        }
        awaitCompletion(completed);
    }

    private void attemptOnExecutor(PublishTarget target) {
        LogEvents.info(log)
                .action(EventAction.SYNC_PUBLISH_START)
                .outcome(EventOutcome.UNKNOWN)
                .field(LogField.IOC_SYNC_TARGET, target.targetId())
                .field(LogField.IOC_SYNC_ENDPOINT, target.endpoint())
                .field(LogField.IOC_EXPORT_PROFILE, target.exportProfile())
                .message("scheduled remote publish started")
                .log();
        try {
            ArtifactPublishResult result = publisher.publish(command(target));
            healthState.recordPublish(
                    target.targetId(), target.endpoint(), target.exportProfile(), result);
            LogEvents.info(log)
                    .action(EventAction.SYNC_PUBLISH_COMPLETE)
                    .outcome(result.failed() == 0 ? EventOutcome.SUCCESS : EventOutcome.FAILURE)
                    .field(LogField.IOC_SYNC_TARGET, target.targetId())
                    .field(LogField.IOC_SYNC_ENDPOINT, target.endpoint())
                    .field(LogField.IOC_EXPORT_PROFILE, target.exportProfile())
                    .field(LogField.IOC_SYNC_FILES, result.succeeded())
                    .message("scheduled remote publish completed: pending=" + result.pending()
                            + ", succeeded=" + result.succeeded() + ", failed=" + result.failed()
                            + ", abandoned=" + result.abandoned())
                    .log();
        } catch (RuntimeException failure) {
            logFailure(target, "scheduled remote publish target failed", failure);
        }
    }

    private void awaitCompletion(CountDownLatch completed) {
        boolean interrupted = false;
        while (true) {
            try {
                completed.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ArtifactPublishCommand command(PublishTarget target) {
        return new ArtifactPublishCommand(
                Optional.of(target.exportProfile()), Optional.of(target.targetId()), false);
    }

    private ArtifactPublishCommand reconcileCommand(String profile) {
        return new ArtifactPublishCommand(Optional.of(profile), false);
    }

    private Map<String, List<PublishTarget>> targetsByProfile() {
        Map<String, List<PublishTarget>> grouped = new LinkedHashMap<>();
        for (PublishTarget target : targets) {
            grouped.computeIfAbsent(target.exportProfile(), ignored -> new ArrayList<>())
                    .add(target);
        }
        return grouped;
    }

    private void logFailure(PublishTarget target, String message, RuntimeException failure) {
        healthState.recordPublishFailure(
                target.targetId(), target.endpoint(), target.exportProfile(), failure);
        LogEvents.error(log)
                .action(EventAction.SYNC_PUBLISH_COMPLETE)
                .outcome(EventOutcome.FAILURE)
                .field(LogField.IOC_SYNC_TARGET, target.targetId())
                .field(LogField.IOC_SYNC_ENDPOINT, target.endpoint())
                .field(LogField.IOC_EXPORT_PROFILE, target.exportProfile())
                .message(message)
                .log(failure);
    }

    @Override
    public void stop() {
        cycle.stop();
    }

    @Override
    public boolean isRunning() {
        return cycle.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

}
