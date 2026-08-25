package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsUseCase;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportReplaySnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.diagnostics.codes.ImportDiagnosticCodes;
import com.iocextractor.platform.events.ControlEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Ledger-first admission and bounded pre-staging recovery for managed imports.
 *
 * <p>The global sequence is reserved before transport work. Claim failures are
 * persisted as due times, never implemented by sleeping a detector thread.</p>
 */
public final class DataframeImportAdmissionService
        implements AdmitDataframeImportUseCase, RecoverDataframeImportsUseCase {

    private final ImportDeliveryLedger ledger;
    private final ManagedImportSourceLifecycle sources;
    private final ControlEventPublisher events;
    private final ImportReplaySnapshotStore replays;
    private final DataframeImportObserver observer;
    private final Clock clock;
    private final Duration retryDelay;

    /** Creates one admission service over shared durable order and source ownership. */
    public DataframeImportAdmissionService(ImportDeliveryLedger ledger,
                                           ManagedImportSourceLifecycle sources,
                                           ControlEventPublisher events,
                                           Clock clock,
                                           Duration retryDelay) {
        this(ledger, sources, events, clock, retryDelay,
                (terminalDeliveryId, replayDeliveryId) -> {
                    throw new IllegalStateException("Import replay snapshot store is not configured");
                }, NoopDataframeImportObserver.INSTANCE);
    }

    /** Creates admission with protected terminal replay materialization. */
    public DataframeImportAdmissionService(ImportDeliveryLedger ledger,
                                           ManagedImportSourceLifecycle sources,
                                           ControlEventPublisher events,
                                           Clock clock,
                                           Duration retryDelay,
                                           ImportReplaySnapshotStore replays) {
        this(ledger, sources, events, clock, retryDelay, replays,
                NoopDataframeImportObserver.INSTANCE);
    }

    /** Creates admission with protected replay materialization and operational observation. */
    public DataframeImportAdmissionService(ImportDeliveryLedger ledger,
                                           ManagedImportSourceLifecycle sources,
                                           ControlEventPublisher events,
                                           Clock clock,
                                           Duration retryDelay,
                                           ImportReplaySnapshotStore replays,
                                           DataframeImportObserver observer) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.events = Objects.requireNonNull(events, "events");
        this.replays = Objects.requireNonNull(replays, "replays");
        this.observer = new ResilientDataframeImportObserver(observer);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("Import retry delay must not be negative");
        }
    }

    @Override
    public AdmitDataframeImportResult admit(AdmitDataframeImportCommand command) {
        Objects.requireNonNull(command, "command");
        ImportClaimReservation reservation = command.reservation();
        ImportDelivery reserved = ledger.reserveClaim(reservation);
        boolean newlyReserved = reserved.id().equals(reservation.deliveryId());
        if (!newlyReserved) {
            return new AdmitDataframeImportResult(reserved, false);
        }
        observer.deliveryDetected(reserved);
        return new AdmitDataframeImportResult(advanceClaim(reserved), true);
    }

    @Override
    public RecoverDataframeImportsResult recover(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Import recovery limit must be positive");
        }
        Instant now = clock.instant();
        int examined = 0;
        int advanced = 0;
        int contradictions = 0;
        for (ImportDelivery delivery : ledger.findRecoverable(limit)) {
            examined++;
            if (!claimRecoveryState(delivery.state()) || !due(delivery, now)) {
                continue;
            }
            try {
                ImportDelivery result = advanceClaim(delivery);
                if (result.state().ordinal() > delivery.state().ordinal()) {
                    advanced++;
                }
            } catch (IllegalStateException contradiction) {
                contradictions++;
            }
        }
        return new RecoverDataframeImportsResult(examined, advanced, contradictions);
    }

    private ImportDelivery advanceClaim(ImportDelivery initial) {
        Instant startedAt = clock.instant();
        ImportDelivery current = initial;
        if (current.state() == ImportDeliveryState.DETECTED) {
            current = transition(current, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        }
        if (current.state() == ImportDeliveryState.CLAIMING) {
            ImportSnapshot snapshot;
            try {
                snapshot = claim(current);
            } catch (RuntimeException failure) {
                return defer(current, failure);
            }
            current = transition(current, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
            ImportDelivery pinned = pinSnapshot(current, snapshot);
            observer.claimCompleted(pinned, elapsedSince(startedAt));
            return pinned;
        }
        if (current.state() == ImportDeliveryState.CLAIMED) {
            try {
                ImportDelivery pinned = pinSnapshot(current, claim(current));
                observer.claimCompleted(pinned, elapsedSince(startedAt));
                return pinned;
            } catch (RuntimeException failure) {
                return defer(current, failure);
            }
        }
        return current;
    }

    private ImportSnapshot claim(ImportDelivery delivery) {
        if (delivery.replayOf().isPresent()) {
            return replays.materializeReplay(delivery.replayOf().orElseThrow(), delivery.id());
        }
        return sources.claim(new ClaimImportSourceCommand(
                delivery.id(), delivery.sourceId(), delivery.candidateToken())).snapshot();
    }

    private ImportDelivery pinSnapshot(ImportDelivery claimed, ImportSnapshot snapshot) {
        ImportDelivery pinned = transition(
                claimed,
                ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(snapshot));
        if (pinned.state() == ImportDeliveryState.SNAPSHOT_PINNED) {
            publishPinned(pinned);
        }
        return pinned;
    }

    private ImportDelivery defer(ImportDelivery delivery, RuntimeException failure) {
        Instant occurredAt = clock.instant();
        ImportRetrySchedule retry = new ImportRetrySchedule(
                delivery.id(), delivery.state(), delivery.version(),
                occurredAt.plus(retryDelay), ImportDiagnosticCodes.CLAIM_FAILED.id(), true, occurredAt);
        ImportLedgerTransitionResult result = ledger.scheduleRetry(retry);
        if (result != ImportLedgerTransitionResult.APPLIED
                && result != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw new IllegalStateException("Import claim retry state conflicts with durable delivery");
        }
        ImportDelivery deferred = required(delivery);
        observer.retryScheduled(deferred, Optional.of(failure.getClass().getName()));
        return deferred;
    }

    private ImportDelivery transition(ImportDelivery current,
                                      ImportDeliveryState next,
                                      ImportDeliveryCheckpoint checkpoint) {
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), next, Optional.empty(),
                checkpoint, Optional.empty(), clock.instant());
        ImportLedgerTransitionResult result = ledger.transition(transition);
        if (result != ImportLedgerTransitionResult.APPLIED
                && result != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw new IllegalStateException("Import admission state conflicts with durable delivery");
        }
        return required(current);
    }

    private ImportDelivery required(ImportDelivery delivery) {
        return ledger.find(delivery.id()).orElseThrow(
                () -> new IllegalStateException("Import delivery disappeared during admission"));
    }

    private void publishPinned(ImportDelivery delivery) {
        try {
            events.publish(ImportDeliverySnapshotPinned.from(
                    delivery.id(), delivery.sequence(), delivery.sourceId(), clock.instant()));
        } catch (RuntimeException ignored) {
            // Durable head reconciliation owns correctness when this latency hint is lost.
        }
    }

    private boolean claimRecoveryState(ImportDeliveryState state) {
        return state == ImportDeliveryState.DETECTED
                || state == ImportDeliveryState.CLAIMING
                || state == ImportDeliveryState.CLAIMED;
    }

    private boolean due(ImportDelivery delivery, Instant now) {
        return delivery.nextAttemptAt().map(deadline -> !deadline.isAfter(now)).orElse(true);
    }

    private Duration elapsedSince(Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        return elapsed.isNegative() ? Duration.ZERO : elapsed;
    }
}
