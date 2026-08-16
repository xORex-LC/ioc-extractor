package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.port.in.artifact.lifecycle.ReconcileExpiredRecordsUseCase;
import com.iocextractor.application.port.out.artifact.lifecycle.ExpiredArtifactStore;
import com.iocextractor.application.port.out.artifact.lifecycle.LifecycleReconciliationStore;
import com.iocextractor.platform.events.ControlEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Application orchestration for recoverable bounded expiration reconciliation. */
public final class LifecycleReconciliationService implements ReconcileExpiredRecordsUseCase {

    public static final String INTERRUPTED_FAILURE = "LIFECYCLE.RECONCILIATION_INTERRUPTED";
    public static final String RUNTIME_FAILURE = "LIFECYCLE.RECONCILIATION_FAILED";

    private final List<String> artifacts;
    private final ExpiredArtifactStore expiredArtifacts;
    private final LifecycleReconciliationStore cycles;
    private final LifecycleTimeSource timeSource;
    private final ControlEventPublisher events;
    private final int batchSize;
    private final Runnable batchYield;

    /** Creates a reconciler that yields between bounded SQLite transactions. */
    public LifecycleReconciliationService(List<String> artifacts,
                                          ExpiredArtifactStore expiredArtifacts,
                                          LifecycleReconciliationStore cycles,
                                          LifecycleTimeSource timeSource,
                                          ControlEventPublisher events,
                                          int batchSize) {
        this(artifacts, expiredArtifacts, cycles, timeSource, events, batchSize, Thread::yield);
    }

    LifecycleReconciliationService(List<String> artifacts,
                                   ExpiredArtifactStore expiredArtifacts,
                                   LifecycleReconciliationStore cycles,
                                   LifecycleTimeSource timeSource,
                                   ControlEventPublisher events,
                                   int batchSize,
                                   Runnable batchYield) {
        this.artifacts = requireArtifacts(artifacts);
        this.expiredArtifacts = Objects.requireNonNull(expiredArtifacts, "expiredArtifacts");
        this.cycles = Objects.requireNonNull(cycles, "cycles");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.events = Objects.requireNonNull(events, "events");
        this.batchSize = requirePositive(batchSize);
        this.batchYield = Objects.requireNonNull(batchYield, "batchYield");
    }

    @Override
    public LifecycleReconciliationResult reconcile() {
        EffectiveTime cycleAsOf = timeSource.now();
        cycles.failInterrupted(cycleAsOf, INTERRUPTED_FAILURE);
        LifecycleReconcileCycleId cycleId = cycles.start(cycleAsOf);
        int expired = 0;
        int batches = 0;
        Set<String> affected = new LinkedHashSet<>();
        try {
            for (String artifact : artifacts) {
                boolean moreDue;
                do {
                    ExpiryBatchResult batch = expiredArtifacts.expireDue(artifact, cycleAsOf, batchSize);
                    batches++;
                    moreDue = batch.moreDue();
                    if (batch.expired() > 0) {
                        expired = Math.addExact(expired, batch.expired());
                        cycles.recordBatch(cycleId, batch.expired());
                        if (affected.add(artifact)) {
                            publishProjectionHint(cycleId, batch);
                        }
                    }
                    if (moreDue) {
                        batchYield.run();
                    }
                } while (moreDue);
            }
            EffectiveTime completedAt = timeSource.now();
            cycles.complete(cycleId, completedAt, expired, affected.size());
            return new LifecycleReconciliationResult(
                    cycleId, cycleAsOf, expired, batches, List.copyOf(affected));
        } catch (RuntimeException failure) {
            try {
                cycles.fail(cycleId, cycleAsOf, RUNTIME_FAILURE);
            } catch (RuntimeException accountingFailure) {
                failure.addSuppressed(accountingFailure);
            }
            throw failure;
        }
    }

    private void publishProjectionHint(LifecycleReconcileCycleId cycleId, ExpiryBatchResult batch) {
        try {
            events.publish(MutableArtifactProjectionRequired.from(
                    "expiry-cycle-" + cycleId.value(),
                    batch.artifactName(),
                    batch.requiredProjectionGeneration(),
                    batch.cycleAsOf().value()));
        } catch (RuntimeException ignored) {
            // Durable required_generation plus periodic convergence is authoritative.
        }
    }

    private static List<String> requireArtifacts(List<String> source) {
        Objects.requireNonNull(source, "artifacts");
        List<String> copy = new ArrayList<>(source.size());
        Set<String> unique = new LinkedHashSet<>();
        for (String artifact : source) {
            if (artifact == null || artifact.isBlank()) {
                throw new IllegalArgumentException("artifact name must not be blank");
            }
            if (!unique.add(artifact)) {
                throw new IllegalArgumentException("duplicate artifact: " + artifact);
            }
            copy.add(artifact);
        }
        return List.copyOf(copy);
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return value;
    }
}
