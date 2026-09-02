package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractFingerprint;
import com.iocextractor.application.dataframeimport.model.ImportContractId;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportPromotionOutcome;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSha256;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportStageReference;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportResult;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.application.port.out.dataframeimport.DataframeImportObserver;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.PublishImportReportCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportPromotionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    void returnsIdleWhenThereIsNoDueHeadOrAnotherStateOwnsTheHead() {
        TestLedger empty = new TestLedger(Optional.empty());
        TestLedger detected = new TestLedger(Optional.of(delivery(
                ImportDeliveryState.DETECTED, emptyEvidence())));

        assertThat(service(empty, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext()
                .workPerformed()).isFalse();
        assertThat(service(detected, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext()
                .workPerformed()).isFalse();
        assertThat(empty.transitions).isEmpty();
        assertThat(detected.transitions).isEmpty();
    }

    @Test
    void advancesAStagedDeliveryAcrossTheCanonicalReceiptBoundary() {
        TestLedger ledger = new TestLedger(Optional.of(delivery(
                ImportDeliveryState.STAGED, completeEvidence())));
        AtomicReference<CanonicalImportCommand> written = new AtomicReference<>();
        AtomicReference<ObservedPromotion> observed = new AtomicReference<>();
        CanonicalImportResult receipt = result();
        Clock clock = new SequenceClock(
                NOW,
                NOW.plusSeconds(1),
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                NOW.plusSeconds(5));
        DataframeImportObserver observer = observer(observed);

        var outcome = service(ledger, command -> {
            written.set(command);
            return receipt;
        }, clock, observer).processNext();

        assertThat(outcome.workPerformed()).isTrue();
        assertThat(outcome.deliveryId()).contains(new ImportDeliveryId("delivery-1"));
        assertThat(ledger.transitions).extracting(ImportDeliveryTransition::nextState)
                .containsExactly(ImportDeliveryState.PROMOTING, ImportDeliveryState.CANONICAL_COMMITTED);
        assertThat(written.get()).satisfies(command -> {
            assertThat(command.deliveryId()).isEqualTo(new ImportDeliveryId("delivery-1"));
            assertThat(command.snapshot()).isEqualTo(snapshot());
            assertThat(command.contract()).isEqualTo(contract());
            assertThat(command.stage()).isEqualTo(stage());
        });
        assertThat(observed.get()).satisfies(event -> {
            assertThat(event.delivery().state()).isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
            assertThat(event.result()).isEqualTo(receipt);
            assertThat(event.duration()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void acceptsIdempotentTransitionsAndContainsObserverFailure() {
        TestLedger ledger = new TestLedger(Optional.of(delivery(
                ImportDeliveryState.STAGED, completeEvidence())));
        ledger.stagingResult = ImportLedgerTransitionResult.ALREADY_APPLIED;
        ledger.commitResult = ImportLedgerTransitionResult.ALREADY_APPLIED;
        Clock clock = new SequenceClock(
                NOW,
                NOW,
                NOW.plusSeconds(5),
                NOW.plusSeconds(4),
                NOW.plusSeconds(3));
        DataframeImportObserver failingObserver = failingObserver();

        assertThat(service(ledger, command -> result(), clock, failingObserver)
                .processNext().workPerformed()).isTrue();
        assertThat(ledger.current.orElseThrow().state())
                .isEqualTo(ImportDeliveryState.CANONICAL_COMMITTED);
    }

    @Test
    void leavesTheHeadUntouchedWhenStagingCasCannotAdvance() {
        for (ImportLedgerTransitionResult transitionResult : List.of(
                ImportLedgerTransitionResult.MISSING,
                ImportLedgerTransitionResult.CONFLICT)) {
            TestLedger ledger = new TestLedger(Optional.of(delivery(
                    ImportDeliveryState.STAGED, completeEvidence())));
            ledger.stagingResult = transitionResult;

            assertThat(service(ledger, command -> {
                throw new AssertionError("canonical promotion must not run");
            }, Clock.fixed(NOW, ZoneOffset.UTC)).processNext().workPerformed())
                    .as(transitionResult.name())
                    .isFalse();
            assertThat(ledger.current.orElseThrow().state()).isEqualTo(ImportDeliveryState.STAGED);
        }
    }

    @Test
    void failsClosedWhenAReceiptCannotAdvanceTheServiceLedger() {
        for (ImportLedgerTransitionResult transitionResult : List.of(
                ImportLedgerTransitionResult.MISSING,
                ImportLedgerTransitionResult.CONFLICT)) {
            TestLedger ledger = new TestLedger(Optional.of(delivery(
                    ImportDeliveryState.PROMOTING, completeEvidence())));
            ledger.commitResult = transitionResult;

            assertThatThrownBy(() -> service(
                    ledger, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext())
                    .as(transitionResult.name())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Canonical import receipt could not advance the service ledger");
        }
    }

    @Test
    void rejectsAHeadThatDisappearsAfterEitherDurableTransition() {
        TestLedger afterStaging = new TestLedger(Optional.of(delivery(
                ImportDeliveryState.STAGED, completeEvidence())));
        afterStaging.disappearAfterStaging = true;
        assertThatThrownBy(() -> service(
                afterStaging, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Promoting import delivery disappeared");

        TestLedger afterCommit = new TestLedger(Optional.of(delivery(
                ImportDeliveryState.PROMOTING, completeEvidence())));
        afterCommit.disappearAfterCommit = true;
        assertThatThrownBy(() -> service(
                afterCommit, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Committed import delivery disappeared");
    }

    @Test
    void requiresEveryPinnedEvidenceElementBeforePromotion() {
        List<ImportDeliveryEvidence> incomplete = List.of(
                emptyEvidence(),
                new ImportDeliveryEvidence(Optional.of(snapshot()), Optional.empty(), Optional.empty()),
                new ImportDeliveryEvidence(
                        Optional.of(snapshot()), Optional.of(contract()), Optional.empty()));
        List<String> messages = List.of(
                "Promoting import has no pinned snapshot",
                "Promoting import has no pinned contract",
                "Promoting import has no pinned stage");

        for (int index = 0; index < incomplete.size(); index++) {
            TestLedger ledger = new TestLedger(Optional.of(delivery(
                    ImportDeliveryState.PROMOTING, incomplete.get(index))));
            String message = messages.get(index);
            assertThatThrownBy(() -> service(
                    ledger, command -> result(), Clock.fixed(NOW, ZoneOffset.UTC)).processNext())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message);
        }
    }

    private DataframeImportPromotionService service(
            TestLedger ledger,
            CanonicalImportWriter writer,
            Clock clock) {
        return new DataframeImportPromotionService(ledger, writer, clock);
    }

    private DataframeImportPromotionService service(
            TestLedger ledger,
            CanonicalImportWriter writer,
            Clock clock,
            DataframeImportObserver observer) {
        return new DataframeImportPromotionService(ledger, writer, clock, observer);
    }

    private DataframeImportObserver observer(AtomicReference<ObservedPromotion> observed) {
        return new DataframeImportObserver() {
            @Override
            public void deliveryDetected(ImportDelivery delivery) {
            }

            @Override
            public void claimCompleted(ImportDelivery delivery, Duration duration) {
            }

            @Override
            public void stagingCompleted(ImportDelivery delivery, Duration duration) {
            }

            @Override
            public void promotionCompleted(
                    ImportDelivery delivery,
                    CanonicalImportResult result,
                    Duration duration) {
                observed.set(new ObservedPromotion(delivery, result, duration));
            }

            @Override
            public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
            }

            @Override
            public void deliveryCompleted(
                    ImportDelivery delivery,
                    PublishImportReportCommand report,
                    Duration duration) {
            }
        };
    }

    private DataframeImportObserver failingObserver() {
        return new DataframeImportObserver() {
            @Override
            public void deliveryDetected(ImportDelivery delivery) {
            }

            @Override
            public void claimCompleted(ImportDelivery delivery, Duration duration) {
            }

            @Override
            public void stagingCompleted(ImportDelivery delivery, Duration duration) {
            }

            @Override
            public void promotionCompleted(
                    ImportDelivery delivery,
                    CanonicalImportResult result,
                    Duration duration) {
                throw new UnsupportedOperationException("observer unavailable");
            }

            @Override
            public void retryScheduled(ImportDelivery delivery, Optional<String> errorType) {
            }

            @Override
            public void deliveryCompleted(
                    ImportDelivery delivery,
                    PublishImportReportCommand report,
                    Duration duration) {
            }
        };
    }

    private ImportDelivery delivery(ImportDeliveryState state, ImportDeliveryEvidence evidence) {
        return new ImportDelivery(
                new ImportDeliveryId("delivery-1"),
                new ImportDeliverySequence(1),
                new ImportSourceId("source-1"),
                "candidate-1",
                Optional.empty(),
                state,
                1,
                evidence,
                new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                Optional.empty(),
                NOW,
                NOW);
    }

    private ImportDeliveryEvidence completeEvidence() {
        return new ImportDeliveryEvidence(
                Optional.of(snapshot()), Optional.of(contract()), Optional.of(stage()));
    }

    private ImportDeliveryEvidence emptyEvidence() {
        return new ImportDeliveryEvidence(Optional.empty(), Optional.empty(), Optional.empty());
    }

    private ImportSnapshot snapshot() {
        return new ImportSnapshot(
                new ImportSnapshotReference("snapshot:test"), new ImportSha256(DIGEST), 10);
    }

    private ImportContractPin contract() {
        return new ImportContractPin(
                new ImportContractId("ip-list-v1"), 1, new ImportContractFingerprint(DIGEST));
    }

    private ImportStage stage() {
        return new ImportStage(
                new ImportStageReference("stage:test"), new ImportSha256(DIGEST), 1, 1, 0);
    }

    private CanonicalImportResult result() {
        return new CanonicalImportResult(
                ImportPromotionOutcome.COMMITTED,
                1,
                0,
                1,
                Set.of("ip_list"),
                Set.of("ip_list"),
                Map.of("ip_list", 1L),
                NOW);
    }

    private record ObservedPromotion(
            ImportDelivery delivery,
            CanonicalImportResult result,
            Duration duration) {
    }

    private static final class SequenceClock extends Clock {

        private final ArrayDeque<Instant> instants;

        private SequenceClock(Instant... instants) {
            this.instants = new ArrayDeque<>(List.of(instants));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instants.removeFirst();
        }
    }

    private static final class TestLedger implements ImportDeliveryLedger {

        private Optional<ImportDelivery> current;
        private final List<ImportDeliveryTransition> transitions = new ArrayList<>();
        private ImportLedgerTransitionResult stagingResult = ImportLedgerTransitionResult.APPLIED;
        private ImportLedgerTransitionResult commitResult = ImportLedgerTransitionResult.APPLIED;
        private boolean disappearAfterStaging;
        private boolean disappearAfterCommit;

        private TestLedger(Optional<ImportDelivery> current) {
            this.current = current;
        }

        @Override
        public ImportDelivery reserveClaim(ImportClaimReservation reservation) {
            throw new AssertionError("claim reservation must not be called");
        }

        @Override
        public Optional<ImportDelivery> find(ImportDeliveryId deliveryId) {
            return current;
        }

        @Override
        public Optional<ImportDelivery> findHead() {
            throw new AssertionError("unconditional head lookup must not be called");
        }

        @Override
        public Optional<ImportDelivery> findDueHead(Instant now) {
            return current;
        }

        @Override
        public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) {
            transitions.add(transition);
            ImportLedgerTransitionResult result = transition.nextState() == ImportDeliveryState.PROMOTING
                    ? stagingResult
                    : commitResult;
            if (result == ImportLedgerTransitionResult.APPLIED
                    || result == ImportLedgerTransitionResult.ALREADY_APPLIED) {
                ImportDelivery previous = current.orElseThrow();
                current = Optional.of(new ImportDelivery(
                        previous.id(), previous.sequence(), previous.sourceId(), previous.candidateToken(),
                        previous.replayOf(), transition.nextState(), previous.version() + 1,
                        previous.evidence(), previous.retry(), Optional.empty(),
                        previous.createdAt(), transition.occurredAt()));
                if ((transition.nextState() == ImportDeliveryState.PROMOTING && disappearAfterStaging)
                        || (transition.nextState() == ImportDeliveryState.CANONICAL_COMMITTED
                                && disappearAfterCommit)) {
                    current = Optional.empty();
                }
            }
            return result;
        }

        @Override
        public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) {
            throw new AssertionError("retry scheduling must not be called");
        }

        @Override
        public List<ImportDelivery> findRecoverable(int limit) {
            throw new AssertionError("recovery listing must not be called");
        }

        @Override
        public List<ImportDelivery> findRetentionCandidates(
                ImportTerminalRetentionTarget target,
                Instant now,
                int limit) {
            throw new AssertionError("retention listing must not be called");
        }

        @Override
        public boolean purgeTerminal(ImportDeliveryId deliveryId, long expectedVersion) {
            throw new AssertionError("terminal purge must not be called");
        }
    }
}
