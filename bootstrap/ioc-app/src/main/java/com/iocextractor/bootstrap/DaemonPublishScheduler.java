package com.iocextractor.bootstrap;

import com.iocextractor.application.port.in.sync.ArtifactPublishCommand;
import com.iocextractor.application.port.in.sync.ArtifactPublishResult;
import com.iocextractor.application.port.in.sync.ArtifactPublishUseCase;
import com.iocextractor.application.sync.PublishTarget;
import com.iocextractor.observability.EventAction;
import com.iocextractor.observability.EventOutcome;
import com.iocextractor.observability.LogField;
import com.iocextractor.observability.logging.LogEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final PeriodicDaemonCycle cycle;

    /** Creates one sequential publish scheduler over the configured target order. */
    public DaemonPublishScheduler(List<PublishTarget> targets,
                                  ArtifactPublishUseCase publisher,
                                  TransportRegistry transports,
                                  SyncHealthState healthState,
                                  Duration interval) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.healthState = Objects.requireNonNull(healthState, "healthState");
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
        for (PublishTarget target : targets) {
            try {
                publisher.reconcile(command(target));
            } catch (RuntimeException failure) {
                logFailure(target, "startup publish reconciliation failed", failure);
            }
        }
    }

    /** Executes one non-overlapping publish cycle and isolates failures by target. */
    public void runOnce() {
        cycle.runOnce();
    }

    private void runCycle() {
        try {
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

    private ArtifactPublishCommand command(PublishTarget target) {
        return new ArtifactPublishCommand(
                Optional.of(target.exportProfile()), Optional.of(target.targetId()), false);
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
