package com.iocextractor.application.tck.dataframeimport;

import com.iocextractor.application.tck.junit.ContractTest;
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
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reusable ordering, idempotency and CAS contract for import delivery ledgers. */
@ContractTest
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

    @Test
    void missingAndStaleCasRequestsReturnExplicitOutcomes() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDeliveryId missing = new ImportDeliveryId("delivery-missing");
        ImportDeliveryTransition missingTransition = new ImportDeliveryTransition(
                missing, ImportDeliveryState.DETECTED, 0, ImportDeliveryState.CLAIMING,
                Optional.empty(), DETECTED_AT.plusSeconds(1));
        ImportRetrySchedule missingRetry = new ImportRetrySchedule(
                missing, ImportDeliveryState.DETECTED, 0, DETECTED_AT.plusSeconds(10),
                "IMPORT.RETRY", true, DETECTED_AT.plusSeconds(1));

        assertThat(ledger.transition(missingTransition)).isEqualTo(ImportLedgerTransitionResult.MISSING);
        assertThat(ledger.scheduleRetry(missingRetry)).isEqualTo(ImportLedgerTransitionResult.MISSING);

        ImportDelivery current = ledger.reserveClaim(reservation("delivery-stale", "candidate-stale"));
        ImportDeliveryTransition staleTransition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version() + 1, ImportDeliveryState.CLAIMING,
                Optional.empty(), DETECTED_AT.plusSeconds(2));
        assertThat(ledger.transition(staleTransition)).isEqualTo(ImportLedgerTransitionResult.CONFLICT);

        ImportRetrySchedule applied = new ImportRetrySchedule(
                current.id(), current.state(), current.version(), DETECTED_AT.plusSeconds(20),
                "IMPORT.RETRY", true, DETECTED_AT.plusSeconds(2));
        assertThat(ledger.scheduleRetry(applied)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        assertThat(ledger.scheduleRetry(new ImportRetrySchedule(
                current.id(), current.state(), current.version() + 2, applied.nextAttemptAt(),
                applied.safeCode(), true, DETECTED_AT.plusSeconds(3))))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);
        assertThat(ledger.scheduleRetry(new ImportRetrySchedule(
                current.id(), current.state(), current.version(), DETECTED_AT.plusSeconds(21),
                applied.safeCode(), true, DETECTED_AT.plusSeconds(3))))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);
        assertThat(ledger.scheduleRetry(new ImportRetrySchedule(
                current.id(), current.state(), current.version(), applied.nextAttemptAt(),
                "IMPORT.OTHER", true, DETECTED_AT.plusSeconds(3))))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);
    }

    @Test
    void replayedCheckpointsMustMatchExactDurableEvidence() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery current = ledger.reserveClaim(reservation("delivery-evidence", "candidate-evidence"));
        current = advance(ledger, current, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        current = advance(ledger, current, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());

        ImportSnapshot snapshot = new ImportSnapshot(
                new ImportSnapshotReference("snapshot:evidence"),
                new ImportSha256("a".repeat(64)), 10);
        ImportDelivery beforeSnapshot = current;
        current = advance(ledger, current, ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(snapshot));
        assertThat(ledger.transition(new ImportDeliveryTransition(
                beforeSnapshot.id(), beforeSnapshot.state(), beforeSnapshot.version(),
                ImportDeliveryState.SNAPSHOT_PINNED, Optional.empty(),
                ImportDeliveryCheckpoint.snapshot(new ImportSnapshot(
                        new ImportSnapshotReference("snapshot:other"),
                        snapshot.digest(), snapshot.size())),
                Optional.empty(), current.updatedAt())))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);

        ImportContractPin contract = new ImportContractPin(
                new ImportContractId("contract-v1"), 1,
                new ImportContractFingerprint("b".repeat(64)));
        ImportDelivery beforeContract = current;
        current = advance(ledger, current, ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryCheckpoint.contract(contract));
        assertThat(ledger.transition(new ImportDeliveryTransition(
                beforeContract.id(), beforeContract.state(), beforeContract.version(),
                ImportDeliveryState.CONTRACT_PINNED, Optional.empty(),
                ImportDeliveryCheckpoint.contract(new ImportContractPin(
                        new ImportContractId("other-v1"), contract.version(), contract.fingerprint())),
                Optional.empty(), current.updatedAt())))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);

        current = advance(ledger, current, ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        ImportStage stage = new ImportStage(
                new ImportStageReference("stage:evidence"),
                new ImportSha256("c".repeat(64)), 4, 3, 1);
        ImportDelivery beforeStage = current;
        current = advance(ledger, current, ImportDeliveryState.STAGED,
                ImportDeliveryCheckpoint.stage(stage));
        assertThat(ledger.transition(new ImportDeliveryTransition(
                beforeStage.id(), beforeStage.state(), beforeStage.version(),
                ImportDeliveryState.STAGED, Optional.empty(),
                ImportDeliveryCheckpoint.stage(new ImportStage(
                        new ImportStageReference("stage:other"), stage.digest(),
                        stage.sourceRows(), stage.acceptedRows(), stage.rejectedRows())),
                Optional.empty(), current.updatedAt())))
                .isEqualTo(ImportLedgerTransitionResult.CONFLICT);
    }

    @Test
    void duplicateDeliveryIdCannotChangeReservationIdentity() {
        ImportDeliveryLedger ledger = createLedger();
        ImportClaimReservation original = reservation("delivery-identity", "candidate-identity");
        ledger.reserveClaim(original);

        assertThatThrownBy(() -> ledger.reserveClaim(new ImportClaimReservation(
                original.deliveryId(), new ImportSourceId("other-source"), original.candidateToken(),
                original.replayOf(), original.detectedAt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Import delivery identity conflicts with an existing reservation");
        assertThatThrownBy(() -> ledger.reserveClaim(new ImportClaimReservation(
                original.deliveryId(), original.sourceId(), "other-candidate",
                original.replayOf(), original.detectedAt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Import delivery identity conflicts with an existing reservation");
        assertThatThrownBy(() -> ledger.reserveClaim(new ImportClaimReservation(
                original.deliveryId(), original.sourceId(), original.candidateToken(),
                Optional.of(new ImportDeliveryId("delivery-cause")), original.detectedAt())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Import delivery identity conflicts with an existing reservation");
    }

    @Test
    void terminalOutcomeBoundaryAndPurgeCasRemainForwardOnly() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery rejected = ledger.reserveClaim(reservation("delivery-rejected", "candidate-rejected"));
        ImportDelivery preCommit = rejected;
        assertThatThrownBy(() -> ledger.transition(new ImportDeliveryTransition(
                preCommit.id(), preCommit.state(), preCommit.version(), ImportDeliveryState.TERMINAL,
                Optional.of(ImportTerminalOutcome.COMPLETED_WITH_ERRORS), ImportDeliveryCheckpoint.none(),
                Optional.empty(), DETECTED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Pre-commit import delivery may terminate only as REJECTED");
        rejected = terminal(ledger, rejected, ImportTerminalOutcome.REJECTED);
        ImportDelivery terminal = rejected;
        assertThatThrownBy(() -> ledger.transition(new ImportDeliveryTransition(
                terminal.id(), terminal.state(), terminal.version(), ImportDeliveryState.TERMINAL,
                Optional.of(ImportTerminalOutcome.REJECTED), ImportDeliveryCheckpoint.none(),
                Optional.empty(), terminal.updatedAt().plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Terminal import delivery cannot transition");
        assertThatThrownBy(() -> ledger.purgeTerminal(terminal.id(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expected import delivery version must not be negative");
        assertThat(ledger.purgeTerminal(terminal.id(), terminal.version() + 1)).isFalse();
        assertThat(ledger.purgeTerminal(terminal.id(), terminal.version())).isTrue();
        assertThat(ledger.purgeTerminal(terminal.id(), terminal.version())).isFalse();

        ImportDelivery succeeded = ledger.reserveClaim(reservation("delivery-succeeded", "candidate-succeeded"));
        succeeded = advanceToCanonicalCommit(ledger, succeeded);
        succeeded = terminal(ledger, succeeded, ImportTerminalOutcome.SUCCEEDED);
        assertThat(succeeded.terminalOutcome()).contains(ImportTerminalOutcome.SUCCEEDED);
    }

    @Test
    void countOnlyRetentionKeepsNewestTerminalOccurrence() {
        ImportDeliveryLedger ledger = createLedger();
        ImportDelivery first = ledger.reserveClaim(reservation("delivery-retain-first", "candidate-retain-first"));
        first = terminal(ledger, first, ImportTerminalOutcome.REJECTED);
        ImportDelivery second = ledger.reserveClaim(reservation("delivery-retain-second", "candidate-retain-second"));
        second = terminal(ledger, second, ImportTerminalOutcome.REJECTED);
        ImportTerminalRetentionTarget target = new ImportTerminalRetentionTarget(
                "rejected", Set.of(ImportTerminalOutcome.REJECTED),
                null, 1, RetentionAction.DELETE, null);

        assertThat(ledger.findRetentionCandidates(target, DETECTED_AT.plusSeconds(30), 10))
                .containsExactly(first);
        assertThatThrownBy(() -> ledger.findRetentionCandidates(
                target, DETECTED_AT.plusSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Import purge limit must be positive");
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

    private ImportDelivery advanceToCanonicalCommit(ImportDeliveryLedger ledger, ImportDelivery current) {
        current = advance(ledger, current, ImportDeliveryState.CLAIMING, ImportDeliveryCheckpoint.none());
        current = advance(ledger, current, ImportDeliveryState.CLAIMED, ImportDeliveryCheckpoint.none());
        current = advance(ledger, current, ImportDeliveryState.SNAPSHOT_PINNED,
                ImportDeliveryCheckpoint.snapshot(new ImportSnapshot(
                        new ImportSnapshotReference("snapshot:" + current.id().value()),
                        new ImportSha256("d".repeat(64)), 1)));
        current = advance(ledger, current, ImportDeliveryState.CONTRACT_PINNED,
                ImportDeliveryCheckpoint.contract(new ImportContractPin(
                        new ImportContractId("contract-v1"), 1,
                        new ImportContractFingerprint("e".repeat(64)))));
        current = advance(ledger, current, ImportDeliveryState.STAGING, ImportDeliveryCheckpoint.none());
        current = advance(ledger, current, ImportDeliveryState.STAGED,
                ImportDeliveryCheckpoint.stage(new ImportStage(
                        new ImportStageReference("stage:" + current.id().value()),
                        new ImportSha256("f".repeat(64)), 1, 1, 0)));
        current = advance(ledger, current, ImportDeliveryState.PROMOTING, ImportDeliveryCheckpoint.none());
        return advance(ledger, current, ImportDeliveryState.CANONICAL_COMMITTED,
                ImportDeliveryCheckpoint.none());
    }

    private ImportDelivery terminal(ImportDeliveryLedger ledger,
                                    ImportDelivery current,
                                    ImportTerminalOutcome outcome) {
        ImportDeliveryTransition transition = new ImportDeliveryTransition(
                current.id(), current.state(), current.version(), ImportDeliveryState.TERMINAL,
                Optional.of(outcome), ImportDeliveryCheckpoint.none(), Optional.empty(),
                current.updatedAt().plusSeconds(1));
        assertThat(ledger.transition(transition)).isEqualTo(ImportLedgerTransitionResult.APPLIED);
        return ledger.find(current.id()).orElseThrow();
    }

    private ImportClaimReservation reservation(String deliveryId, String candidateToken) {
        return new ImportClaimReservation(
                new ImportDeliveryId(deliveryId), new ImportSourceId("source"), candidateToken, DETECTED_AT);
    }
}
