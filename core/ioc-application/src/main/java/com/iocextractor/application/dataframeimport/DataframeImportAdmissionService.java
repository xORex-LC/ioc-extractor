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
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
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

    private static final String CLAIM_FAILED = "IMPORT.CLAIM_FAILED";

    private final ImportDeliveryLedger ledger;
    private final ManagedImportSourceLifecycle sources;
    private final ControlEventPublisher events;
    private final Clock clock;
    private final Duration retryDelay;

    /** Creates one admission service over shared durable order and source ownership. */
    public DataframeImportAdmissionService(ImportDeliveryLedger ledger,
                                           ManagedImportSourceLifecycle sources,
                                           ControlEventPublisher events,
                                           Clock clock,
                                           Duration retryDelay) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.events = Objects.requireNonNull(events, "events");
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
        ImportDelivery current = initial;
        if (current.state() == ImportDeliveryState.DETECTED) {
            current = transition(current, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        }
        if (current.state() == ImportDeliveryState.CLAIMING) {
            ImportSnapshot snapshot;
            try {
                snapshot = claim(current);
            } catch (RuntimeException failure) {
                return defer(current);
            }
            current = transition(current, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
            return pinSnapshot(current, snapshot);
        }
        if (current.state() == ImportDeliveryState.CLAIMED) {
            try {
                return pinSnapshot(current, claim(current));
            } catch (RuntimeException failure) {
                return defer(current);
            }
        }
        return current;
    }

    private ImportSnapshot claim(ImportDelivery delivery) {
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

    private ImportDelivery defer(ImportDelivery delivery) {
        Instant occurredAt = clock.instant();
        ImportRetrySchedule retry = new ImportRetrySchedule(
                delivery.id(), delivery.state(), delivery.version(),
                occurredAt.plus(retryDelay), CLAIM_FAILED, true, occurredAt);
        ImportLedgerTransitionResult result = ledger.scheduleRetry(retry);
        if (result != ImportLedgerTransitionResult.APPLIED
                && result != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw new IllegalStateException("Import claim retry state conflicts with durable delivery");
        }
        return required(delivery);
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
}
