package com.iocextractor.application.tck.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable ordering, idempotency and CAS contract for import delivery ledgers. */
public abstract class ImportDeliveryLedgerContractTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-08-23T00:00:00Z");

    /** @return a clean ledger instance for one test */
    protected abstract ImportDeliveryLedger createLedger();

    @Test
    void reservationsAreIdempotentAndSequencesAreStrictlyMonotonic() {
        ImportDeliveryLedger ledger = createLedger();
        ImportClaimReservation first = reservation("delivery-a", "candidate-a");
        ImportClaimReservation second = reservation("delivery-b", "candidate-b");

        ImportDelivery firstDelivery = ledger.reserveClaim(first);
        ImportDelivery replayed = ledger.reserveClaim(first);
        ImportDelivery secondDelivery = ledger.reserveClaim(second);

        assertThat(replayed).isEqualTo(firstDelivery);
        assertThat(secondDelivery.sequence()).isGreaterThan(firstDelivery.sequence());
        assertThat(ledger.findHead()).contains(firstDelivery);
    }

    @Test
    void duplicateDetectionReturnsTheActiveOccurrenceWithoutConsumingSequence() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-active", "candidate-active"));

        ImportDelivery duplicate = ledger.reserveClaim(reservation("delivery-duplicate", "candidate-active"));
        ImportDelivery next = ledger.reserveClaim(reservation("delivery-after", "candidate-after"));

        assertThat(duplicate).isEqualTo(first);
        assertThat(ledger.find(new ImportDeliveryId("delivery-duplicate"))).isEmpty();
        // SQLite may burn an AUTOINCREMENT value while resolving the unique
        // active-candidate conflict; monotonic delivery identities are never reused.
        assertThat(next.sequence()).isGreaterThan(first.sequence());
    }

    @Test
    void transitionUsesStateAndVersionCompareAndSet() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery detected = ledger.reserveClaim(reservation("delivery-cas", "candidate-cas"));
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                detected.id(), detected.state(), detected.version(), ImportDeliveryState.CLAIMING,
                Optional.empty(), DETECTED_AT.plusSeconds(1));

        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.ALREADY_APPLIED);
        assertThat(ledger.find(detected.id())).get().satisfies(updated -> {
            assertThat(updated.state()).isEqualTo(ImportDeliveryState.CLAIMING);
            assertThat(updated.version()).isGreaterThan(detected.version());
        });
    }

    @Test
    void recoveryAndHeadRemainInGlobalSequenceOrder() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-first", "candidate-first"));
        ImportDelivery second = ledger.reserveClaim(reservation("delivery-second", "candidate-second"));

        assertThat(ledger.findRecoverable(2)).containsExactly(first, second);
        assertThat(ledger.findHead()).contains(first);
    }

    @Test
    void pinsSnapshotContractAndSealedStageAtTheirExactCasBoundaries() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery current = ledger.reserveClaim(reservation("delivery-checkpoints", "candidate-checkpoints"));
        current = advance(ledger, current, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        current = advance(ledger, current, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());

        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:delivery-checkpoints"),
                new ImportSha256("a".repeat(64)), 1024);
        current = advance(ledger, current, ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(snapshot));
        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("ip-list-v1"), 1,
                new ImportContractFingerprint("b".repeat(64)));
        current = advance(ledger, current, ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryCheckpoint.contract(contract));
        current = advance(ledger, current, ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        ImportStage stage = new ImportStage(
                new ImportStageReference("stage:delivery-checkpoints"),
                new ImportSha256("c".repeat(64)), 10, 8, 2);
        current = advance(ledger, current, ImportDeliveryState.STAGED,
                ImportDeliveryCheckpoint.stage(stage));

        assertThat(current.snapshot()).contains(snapshot);
        assertThat(current.contract()).contains(contract);
        assertThat(current.stage()).contains(stage);
        assertThat(ledger.find(current.id())).contains(current);
    }

    @Test
    void rejectsSkippedAndCheckpointlessTransitionsBeforeMutation() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery detected = ledger.reserveClaim(reservation("delivery-illegal", "candidate-illegal"));

        assertThatThrownBy(() -> ledger.transition(new ImportDeliveryTransition(
                detected.id(), detected.state(), detected.version(), ImportDeliveryState.CLAIMED,
                Optional.empty(), DETECTED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);

        ImportDelivery claiming = advance(
                ledger, detected, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        ImportDelivery claimed = advance(
                ledger, claiming, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
        assertThatThrownBy(() -> ledger.transition(new ImportDeliveryTransition(
                claimed.id(), claimed.state(), claimed.version(), ImportDeliveryState.SNAPSHOT_PINNED,
                Optional.empty(), DETECTED_AT.plusSeconds(3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.find(claimed.id())).contains(claimed);
    }

    @Test
    void retryScheduleNeverLetsLaterDueWorkOvertakeTheDeferredHead() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-deferred", "candidate-deferred"));
        ImportDelivery second = ledger.reserveClaim(reservation("delivery-due", "candidate-due"));
        Instant retryAt = DETECTED_AT.plusSeconds(60);

        assertThat(ledger.scheduleRetry(new ImportRetrySchedule(
                first.id(), first.state(), first.version(), retryAt,
                "IMPORT.CAPACITY_PAUSED", false, DETECTED_AT.plusSeconds(1))))
                .isEqualTo(ImportLedgerTransitionResult.APPLIED);

        ImportDelivery deferred = ledger.find(first.id()).orElseThrow();
        assertThat(deferred.attemptCount()).isZero();
        assertThat(deferred.nextAttemptAt()).contains(retryAt);
        assertThat(ledger.findDueHead(DETECTED_AT.plusSeconds(30))).isEmpty();
        assertThat(ledger.findHead()).contains(deferred);
        assertThat(ledger.findDueHead(retryAt)).contains(deferred);
        assertThat(ledger.findRecoverable(2)).containsExactly(deferred, second);
    }

    @Test
    void actualRetryFailureIncrementsAttemptsAndIsIdempotent() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery delivery = ledger.reserveClaim(reservation("delivery-retry", "candidate-retry"));
        ImportRetrySchedule retry = new ImportRetrySchedule(
                delivery.id(), delivery.state(), delivery.version(), DETECTED_AT.plusSeconds(10),
                "IMPORT.CLAIM_FAILED", true, DETECTED_AT.plusSeconds(1));

        assertThat(ledger.scheduleRetry(retry)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        assertThat(ledger.scheduleRetry(retry)).isEqualTo(ImportLedgerTransitionResult.ALREADY_APPLIED);
        assertThat(ledger.find(delivery.id())).get().satisfies(updated -> {
            assertThat(updated.attemptCount()).isOne();
            assertThat(updated.lastErrorCode()).contains("IMPORT.CLAIM_FAILED");
        });
    }

    @Test
    void terminalRejectedHeadReleasesSequenceAndAllowsNewCandidateOccurrence() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-terminal", "candidate-same"));
        ImportDelivery second = ledger.reserveClaim(reservation("delivery-next", "candidate-next"));

        ImportDeliveryTransition rejected = new ImportDeliveryTransition(
                first.id(), first.state(), first.version(), ImportDeliveryState.TERMINAL,
                Optional.of(ImportTerminalOutcome.REJECTED), ImportDeliveryCheckpoint.none(),
                Optional.of("IMPORT.CONTRACT_NOT_RECOGNIZED"), DETECTED_AT.plusSeconds(1));
        assertThat(ledger.transition(rejected)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        assertThat(ledger.findHead()).contains(second);

        ImportDelivery replay = ledger.reserveClaim(new ImportClaimReservation(
                new ImportDeliveryId("delivery-replay"), first.sourceId(), first.candidateToken(),
                Optional.of(first.id()), DETECTED_AT.plusSeconds(2)));
        assertThat(replay.replayOf()).contains(first.id());
        assertThat(replay.sequence()).isGreaterThan(second.sequence());
    }

    @Test
    void recoverableQueryIsStrictlyBounded() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-bound-1", "candidate-bound-1"));
        ledger.reserveClaim(reservation("delivery-bound-2", "candidate-bound-2"));

        assertThat(ledger.findRecoverable(1)).containsExactly(first);
        assertThatThrownBy(() -> ledger.findRecoverable(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private ImportDelivery advance(ImportDeliveryLedger ledger,
                                   ImportDelivery current,
                                   ImportDeliveryState next,
                                   ImportDeliveryCheckpoint checkpoint) {
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), next, Optional.empty(), checkpoint,
                Optional.empty(), current.updatedAt().plusSeconds(1));
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        return ledger.find(current.id()).orElseThrow();
    }

    private ImportClaimReservation reservation(String deliveryId, String candidateToken) {
        return new ImportClaimReservation(
                new ImportDeliveryId(deliveryId), new ImportSourceId("source"), candidateToken, DETECTED_AT);
    }
}
