package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryCheckpoint;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceCandidate;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.RecoverDataframeImportsResult;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ClaimImportSourceResult;
import com.iocextractor.application.port.out.dataframeimport.DispositionImportSourceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportReplaySnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ManagedImportSourceLifecycle;
import com.iocextractor.platform.events.ControlEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportAdmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final String DIGEST = "a".repeat(64);

    @Test
    void reservesClaimsPinsAndPublishesOnlySafeDeliveryIdentity() {
        TestLedger ledger = new TestLedger();
        TestSources sources = new TestSources();
        AtomicReference<ControlEvent> published = new AtomicReference<>();
        DataframeImportAdmissionService service = service(
                ledger, sources, published::set, unusedReplays());

        var result = service.admit(new AdmitDataframeImportCommand(reservation("delivery-1")));

        assertThat(result.newlyReserved()).isTrue();
        assertThat(result.delivery().state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(result.delivery().snapshot()).contains(snapshot());
        assertThat(ledger.transitions).extracting(ImportDeliveryTransition::nextState)
                .containsExactly(
                        ImportDeliveryState.CLAIMING,
                        ImportDeliveryState.CLAIMED,
                        ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(sources.claims).singleElement().satisfies(command -> {
            assertThat(command.deliveryId().value()).isEqualTo("delivery-1");
            assertThat(command.candidateToken()).isEqualTo("candidate-delivery-1");
        });
        assertThat(published.get()).isInstanceOfSatisfying(
                ImportDeliverySnapshotPinned.class,
                event -> {
                    assertThat(event.deliveryId().value()).isEqualTo("delivery-1");
                    assertThat(event.sourceId().value()).isEqualTo("source-1");
                });
    }

    @Test
    void returnsAnExistingActiveOccurrenceWithoutClaimingItAgain() {
        TestLedger ledger = new TestLedger();
        ImportDelivery existing = delivery("existing", ImportDeliveryState.CLAIMING, Optional.empty());
        ledger.reservationResult = existing;
        TestSources sources = new TestSources();
        DataframeImportAdmissionService service = service(
                ledger, sources, ignored -> { }, unusedReplays());

        var result = service.admit(new AdmitDataframeImportCommand(reservation("new-delivery")));

        assertThat(result.newlyReserved()).isFalse();
        assertThat(result.delivery()).isEqualTo(existing);
        assertThat(sources.claims).isEmpty();
        assertThat(ledger.transitions).isEmpty();
    }

    @Test
    void persistsClaimFailureAsRetryInsteadOfEscapingOrSleeping() {
        TestLedger ledger = new TestLedger();
        TestSources sources = new TestSources();
        sources.failure = new IllegalStateException("transport unavailable");
        DataframeImportAdmissionService service = service(
                ledger, sources, ignored -> { }, unusedReplays());

        var result = service.admit(new AdmitDataframeImportCommand(reservation("delivery-1")));

        assertThat(result.delivery().state()).isEqualTo(ImportDeliveryState.CLAIMING);
        assertThat(ledger.retries).singleElement().satisfies(retry -> {
            assertThat(retry.safeCode()).isEqualTo("IMPORT.CLAIM_FAILED");
            assertThat(retry.failedAttempt()).isTrue();
            assertThat(retry.nextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
        });
    }

    @Test
    void materializesReplayWithoutTouchingTheForwardSource() {
        TestLedger ledger = new TestLedger();
        TestSources sources = new TestSources();
        AtomicReference<ImportDeliveryId> terminal = new AtomicReference<>();
        AtomicReference<ImportDeliveryId> replay = new AtomicReference<>();
        ImportReplaySnapshotStore replays = (terminalId, replayId) -> {
            terminal.set(terminalId);
            replay.set(replayId);
            return snapshot();
        };
        DataframeImportAdmissionService service = service(
                ledger, sources, ignored -> { }, replays);
        ImportClaimReservation reservation = new ImportClaimReservation(
                new ImportDeliveryId("replay-1"), new ImportSourceId("source-1"),
                "candidate-replay-1", Optional.of(new ImportDeliveryId("terminal-1")), NOW);

        var result = service.admit(new AdmitDataframeImportCommand(reservation));

        assertThat(result.delivery().state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(terminal.get().value()).isEqualTo("terminal-1");
        assertThat(replay.get().value()).isEqualTo("replay-1");
        assertThat(sources.claims).isEmpty();
    }

    @Test
    void defaultReplayBoundaryFailsClosedAndSchedulesRetry() {
        TestLedger ledger = new TestLedger();
        TestSources sources = new TestSources();
        DataframeImportAdmissionService service = new DataframeImportAdmissionService(
                ledger, sources, ignored -> { }, CLOCK, RETRY_DELAY);
        ImportClaimReservation reservation = new ImportClaimReservation(
                new ImportDeliveryId("replay-1"), new ImportSourceId("source-1"),
                "candidate-replay-1", Optional.of(new ImportDeliveryId("terminal-1")), NOW);

        var result = service.admit(new AdmitDataframeImportCommand(reservation));

        assertThat(result.delivery().state()).isEqualTo(ImportDeliveryState.CLAIMING);
        assertThat(result.delivery().attemptCount()).isEqualTo(1);
        assertThat(ledger.retries).singleElement()
                .extracting(ImportRetrySchedule::safeCode)
                .isEqualTo("IMPORT.CLAIM_FAILED");
        assertThat(sources.claims).isEmpty();
    }

    @Test
    void recoveryAdvancesOnlyDueClaimStatesAndCountsDurableContradictions() {
        TestLedger ledger = new TestLedger();
        ImportDelivery detected = delivery("detected", ImportDeliveryState.DETECTED, Optional.empty());
        ImportDelivery deferred = delivery(
                "deferred", ImportDeliveryState.CLAIMING, Optional.of(NOW.plusSeconds(1)));
        ImportDelivery conflicting = delivery(
                "conflicting", ImportDeliveryState.CLAIMED, Optional.of(NOW));
        ImportDelivery alreadyPinned = delivery(
                "pinned", ImportDeliveryState.SNAPSHOT_PINNED, Optional.empty());
        ledger.recoverable = List.of(detected, deferred, conflicting, alreadyPinned);
        ledger.addAll(ledger.recoverable);
        ledger.transitionConflicts.add(conflicting.id());
        ledger.retryResult = ImportLedgerTransitionResult.CONFLICT;
        TestSources sources = new TestSources();
        DataframeImportAdmissionService service = service(
                ledger, sources, ignored -> { }, unusedReplays());

        RecoverDataframeImportsResult result = service.recover(4);

        assertThat(sources.claims).hasSize(2);
        assertThat(result).isEqualTo(new RecoverDataframeImportsResult(4, 1, 1));
        assertThat(ledger.find(detected.id()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(ledger.find(deferred.id()).orElseThrow()).isEqualTo(deferred);
        assertThat(ledger.find(alreadyPinned.id()).orElseThrow()).isEqualTo(alreadyPinned);
    }

    @Test
    void recoveryReclaimsAndPinsAnOwnedDeliveryMissingItsSnapshot() {
        TestLedger ledger = new TestLedger();
        ImportDelivery claimed = delivery(
                "claimed", ImportDeliveryState.CLAIMED, Optional.empty());
        ledger.recoverable = List.of(claimed);
        ledger.addAll(ledger.recoverable);
        DataframeImportAdmissionService service = service(
                ledger, new TestSources(), ignored -> { }, unusedReplays());

        RecoverDataframeImportsResult result = service.recover(1);

        assertThat(result).isEqualTo(new RecoverDataframeImportsResult(1, 1, 0));
        assertThat(ledger.find(claimed.id()).orElseThrow().state())
                .isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
    }

    @Test
    void lostEventIsOnlyARecoverableLatencyHint() {
        TestLedger ledger = new TestLedger();
        DataframeImportAdmissionService service = service(
                ledger, new TestSources(), ignored -> {
                    throw new IllegalStateException("publisher unavailable");
                }, unusedReplays());

        var result = service.admit(new AdmitDataframeImportCommand(reservation("delivery-1")));

        assertThat(result.delivery().state()).isEqualTo(ImportDeliveryState.SNAPSHOT_PINNED);
        assertThat(ledger.retries).isEmpty();
    }

    @Test
    void rejectsInvalidBoundsAndDurableRetryConflicts() {
        TestLedger ledger = new TestLedger();
        TestSources sources = new TestSources();
        DataframeImportAdmissionService service = service(
                ledger, sources, ignored -> { }, unusedReplays());

        assertThatIllegalArgumentException().isThrownBy(() -> service.recover(0))
                .withMessage("Import recovery limit must be positive");
        assertThatIllegalArgumentException().isThrownBy(() -> new DataframeImportAdmissionService(
                ledger, sources, ignored -> { }, CLOCK, Duration.ofNanos(-1)))
                .withMessage("Import retry delay must not be negative");

        sources.failure = new IllegalStateException("transport unavailable");
        ledger.retryResult = ImportLedgerTransitionResult.CONFLICT;
        assertThatThrownBy(() -> service.admit(
                new AdmitDataframeImportCommand(reservation("delivery-1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Import claim retry state conflicts with durable delivery");
    }

    private DataframeImportAdmissionService service(
            TestLedger ledger,
            ManagedImportSourceLifecycle sources,
            com.iocextractor.platform.events.ControlEventPublisher events,
            ImportReplaySnapshotStore replays) {
        return new DataframeImportAdmissionService(
                ledger, sources, events, CLOCK, RETRY_DELAY, replays);
    }

    private ImportReplaySnapshotStore unusedReplays() {
        return (terminalDeliveryId, replayDeliveryId) -> {
            throw new AssertionError("replay materialization must not be called");
        };
    }

    private ImportClaimReservation reservation(String id) {
        return new ImportClaimReservation(
                new ImportDeliveryId(id), new ImportSourceId("source-1"),
                "candidate-" + id, NOW);
    }

    private static ImportSnapshot snapshot() {
        return new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(DIGEST), 10);
    }

    private static ImportDelivery delivery(
            String id, ImportDeliveryState state, Optional<Instant> nextAttemptAt) {
        Optional<ImportSnapshot> pinnedSnapshot = state.ordinal() >= ImportDeliveryState.SNAPSHOT_PINNED.ordinal()
                ? Optional.of(snapshot())
                : Optional.empty();
        Optional<ImportTerminalOutcome> outcome = state == ImportDeliveryState.TERMINAL
                ? Optional.of(ImportTerminalOutcome.SUCCEEDED)
                : Optional.empty();
        return new ImportDelivery(
                new ImportDeliveryId(id), new ImportDeliverySequence(1),
                new ImportSourceId("source-1"), "candidate-" + id, Optional.empty(),
                state, 1,
                new ImportDeliveryEvidence(pinnedSnapshot, Optional.empty(), Optional.empty()),
                new ImportDeliveryRetryState(0, nextAttemptAt, Optional.empty()),
                outcome, NOW, NOW);
    }

    private static final class TestSources implements ManagedImportSourceLifecycle {
        private final List<ClaimImportSourceCommand> claims = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public List<ImportSourceCandidate> detect(ImportSourceId sourceId, Instant observedAt) {
            throw new AssertionError("source detection must not be called");
        }

        @Override
        public ClaimImportSourceResult claim(ClaimImportSourceCommand command) {
            claims.add(command);
            if (failure != null) {
                throw failure;
            }
            return new ClaimImportSourceResult(snapshot());
        }

        @Override
        public void disposition(DispositionImportSourceCommand command) {
            throw new AssertionError("source disposition must not be called");
        }
    }

    private static final class TestLedger implements ImportDeliveryLedger {
        private final Map<ImportDeliveryId, ImportDelivery> deliveries = new LinkedHashMap<>();
        private final List<ImportDeliveryTransition> transitions = new ArrayList<>();
        private final List<ImportRetrySchedule> retries = new ArrayList<>();
        private final Set<ImportDeliveryId> transitionConflicts = new java.util.HashSet<>();
        private ImportDelivery reservationResult;
        private List<ImportDelivery> recoverable = List.of();
        private ImportLedgerTransitionResult retryResult = ImportLedgerTransitionResult.APPLIED;

        @Override
        public ImportDelivery reserveClaim(ImportClaimReservation reservation) {
            if (reservationResult != null) {
                return reservationResult;
            }
            ImportDelivery reserved = new ImportDelivery(
                    reservation.deliveryId(), new ImportDeliverySequence(deliveries.size() + 1L),
                    reservation.sourceId(), reservation.candidateToken(), reservation.replayOf(),
                    ImportDeliveryState.DETECTED, 0,
                    new ImportDeliveryEvidence(Optional.empty(), Optional.empty(), Optional.empty()),
                    new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                    Optional.empty(), reservation.detectedAt(), reservation.detectedAt());
            deliveries.put(reserved.id(), reserved);
            return reserved;
        }

        @Override
        public Optional<ImportDelivery> find(ImportDeliveryId deliveryId) {
            return Optional.ofNullable(deliveries.get(deliveryId));
        }

        @Override
        public Optional<ImportDelivery> findHead() {
            return deliveries.values().stream().findFirst();
        }

        @Override
        public Optional<ImportDelivery> findDueHead(Instant now) {
            return findHead().filter(delivery -> delivery.nextAttemptAt()
                    .map(deadline -> !deadline.isAfter(now)).orElse(true));
        }

        @Override
        public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) {
            transitions.add(transition);
            if (transitionConflicts.contains(transition.deliveryId())) {
                return ImportLedgerTransitionResult.CONFLICT;
            }
            ImportDelivery current = deliveries.get(transition.deliveryId());
            if (current == null
                    || current.state() != transition.expectedState()
                    || current.version() != transition.expectedVersion()) {
                return ImportLedgerTransitionResult.CONFLICT;
            }
            ImportDeliveryCheckpoint checkpoint = transition.checkpoint();
            ImportDeliveryEvidence evidence = new ImportDeliveryEvidence(
                    checkpoint.snapshot().or(() -> current.evidence().snapshot()),
                    checkpoint.contract().or(() -> current.evidence().contract()),
                    checkpoint.stage().or(() -> current.evidence().stage()));
            ImportDelivery advanced = new ImportDelivery(
                    current.id(), current.sequence(), current.sourceId(), current.candidateToken(),
                    current.replayOf(), transition.nextState(), current.version() + 1, evidence,
                    current.retry(), transition.terminalOutcome(), current.createdAt(), transition.occurredAt());
            deliveries.put(advanced.id(), advanced);
            return ImportLedgerTransitionResult.APPLIED;
        }

        @Override
        public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) {
            retries.add(schedule);
            if (retryResult != ImportLedgerTransitionResult.APPLIED
                    && retryResult != ImportLedgerTransitionResult.ALREADY_APPLIED) {
                return retryResult;
            }
            ImportDelivery current = deliveries.get(schedule.deliveryId());
            ImportDelivery deferred = new ImportDelivery(
                    current.id(), current.sequence(), current.sourceId(), current.candidateToken(),
                    current.replayOf(), current.state(), current.version() + 1, current.evidence(),
                    new ImportDeliveryRetryState(
                            current.attemptCount() + (schedule.failedAttempt() ? 1 : 0),
                            Optional.of(schedule.nextAttemptAt()), Optional.of(schedule.safeCode())),
                    current.terminalOutcome(), current.createdAt(), schedule.occurredAt());
            deliveries.put(deferred.id(), deferred);
            return retryResult;
        }

        @Override
        public List<ImportDelivery> findRecoverable(int limit) {
            return recoverable.stream().limit(limit).toList();
        }

        @Override
        public List<ImportDelivery> findRetentionCandidates(
                ImportTerminalRetentionTarget target, Instant now, int limit) {
            throw new AssertionError("retention listing must not be called");
        }

        @Override
        public boolean purgeTerminal(ImportDeliveryId deliveryId, long expectedVersion) {
            throw new AssertionError("terminal purge must not be called");
        }

        private void addAll(List<ImportDelivery> additions) {
            additions.forEach(delivery -> deliveries.put(delivery.id(), delivery));
        }
    }
}
