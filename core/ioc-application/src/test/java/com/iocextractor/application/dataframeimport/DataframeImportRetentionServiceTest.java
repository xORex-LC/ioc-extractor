package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportContractPin;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryEvidence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryRetryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliverySequence;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.dataframeimport.model.ImportRetrySchedule;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportStage;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.dataframeimport.model.ImportWorkspaceCapacity;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.out.dataframeimport.CreateImportWorkspaceCommand;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalRetentionStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotWriter;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspaceWriter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataframeImportRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final ImportTerminalRetentionTarget TARGET = new ImportTerminalRetentionTarget(
            "successful", Set.of(ImportTerminalOutcome.SUCCEEDED), Duration.ofDays(1), 1,
            RetentionAction.DELETE, null);

    @Test
    void purgesRemoteSourceBeforeLocalEvidenceAndLedgerLast() {
        List<String> order = new ArrayList<>();
        TestLedger ledger = new TestLedger(delivery(Optional.empty()), order);
        DataframeImportRetentionService service = service(ledger, order,
                command -> order.add("remote"));

        assertThat(service.retain(1)).isOne();

        assertThat(order).containsExactly(
                "remote", "terminal", "workspace", "snapshot", "commit", "ledger");
    }

    @Test
    void leavesEveryLocalEvidenceAndLedgerWhenRemotePurgeFails() {
        List<String> order = new ArrayList<>();
        TestLedger ledger = new TestLedger(delivery(Optional.empty()), order);
        DataframeImportRetentionService service = service(ledger, order, command -> {
            order.add("remote");
            throw new IllegalStateException("retry later");
        });

        assertThatThrownBy(() -> service.retain(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retry later");
        assertThat(order).containsExactly("remote");
    }

    @Test
    void replayHasNoTransportSourceRemnant() {
        List<String> order = new ArrayList<>();
        TestLedger ledger = new TestLedger(
                delivery(Optional.of(new ImportDeliveryId("parent"))), order);
        DataframeImportRetentionService service = service(ledger, order, command -> {
            throw new AssertionError("replay must not call terminal-source retention");
        });

        assertThat(service.retain(1)).isOne();
        assertThat(order).containsExactly(
                "terminal", "workspace", "snapshot", "commit", "ledger");
    }

    private DataframeImportRetentionService service(
            TestLedger ledger,
            List<String> order,
            com.iocextractor.application.port.out.dataframeimport.ImportTerminalSourceRetention remote) {
        ImportTerminalRetentionStore terminals = new ImportTerminalRetentionStore() {
            @Override
            public void delete(ImportDeliveryId deliveryId) {
                order.add("terminal");
            }

            @Override
            public void archive(ImportDeliveryId deliveryId, java.nio.file.Path archiveDirectory) {
                throw new AssertionError("archive must not be called");
            }
        };
        ImportCommitEvidenceStore commits = new ImportCommitEvidenceStore() {
            @Override
            public Optional<com.iocextractor.application.dataframeimport.model.ImportCommitEvidence> find(
                    ImportDeliveryId deliveryId) {
                throw new AssertionError("find must not be called");
            }

            @Override
            public void purge(ImportDeliveryId deliveryId) {
                order.add("commit");
            }
        };
        ImportSnapshotStore snapshots = new ImportSnapshotStore() {
            @Override
            public ImportSnapshot materialize(
                    ImportDeliveryId deliveryId, ImportSnapshotWriter writer) {
                throw new AssertionError("materialization must not be called");
            }

            @Override
            public java.nio.file.Path resolve(
                    com.iocextractor.application.dataframeimport.model.ImportSnapshotReference reference) {
                throw new AssertionError("resolution must not be called");
            }

            @Override
            public void purge(ImportDeliveryId deliveryId) {
                order.add("snapshot");
            }
        };
        return new DataframeImportRetentionService(
                ledger, terminals, remote, snapshots, workspace(order), commits,
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(TARGET));
    }

    private ImportWorkspace workspace(List<String> order) {
        return new ImportWorkspace() {
            @Override
            public ImportWorkspaceWriter create(CreateImportWorkspaceCommand command) {
                throw new AssertionError();
            }

            @Override
            public ImportWorkspaceWriter rebuild(CreateImportWorkspaceCommand command) {
                throw new AssertionError();
            }

            @Override
            public ImportStage verifySealed(CreateImportWorkspaceCommand command, ImportStage expected) {
                throw new AssertionError();
            }

            @Override
            public Optional<ImportStage> adoptSealed(
                    ImportDeliveryId deliveryId, ImportSnapshot snapshot, ImportContractPin contract) {
                throw new AssertionError();
            }

            @Override
            public ImportWorkspaceCapacity capacity() {
                throw new AssertionError();
            }

            @Override
            public void discard(ImportDeliveryId deliveryId) {
                order.add("workspace");
            }
        };
    }

    private ImportDelivery delivery(Optional<ImportDeliveryId> replayOf) {
        return new ImportDelivery(
                new ImportDeliveryId("delivery-1"), new ImportDeliverySequence(1),
                new ImportSourceId("source-1"), "candidate-1", replayOf,
                ImportDeliveryState.TERMINAL, 7,
                new ImportDeliveryEvidence(Optional.empty(), Optional.empty(), Optional.empty()),
                new ImportDeliveryRetryState(0, Optional.empty(), Optional.empty()),
                Optional.of(ImportTerminalOutcome.SUCCEEDED), NOW.minus(Duration.ofDays(2)), NOW);
    }

    private static final class TestLedger implements ImportDeliveryLedger {
        private final ImportDelivery delivery;
        private final List<String> order;

        private TestLedger(ImportDelivery delivery, List<String> order) {
            this.delivery = delivery;
            this.order = order;
        }

        @Override public ImportDelivery reserveClaim(ImportClaimReservation reservation) { throw new AssertionError(); }
        @Override public Optional<ImportDelivery> find(ImportDeliveryId deliveryId) { return Optional.of(delivery); }
        @Override public Optional<ImportDelivery> findHead() { throw new AssertionError(); }
        @Override public Optional<ImportDelivery> findDueHead(Instant now) { throw new AssertionError(); }
        @Override public ImportLedgerTransitionResult transition(ImportDeliveryTransition transition) { throw new AssertionError(); }
        @Override public ImportLedgerTransitionResult scheduleRetry(ImportRetrySchedule schedule) { throw new AssertionError(); }
        @Override public List<ImportDelivery> findRecoverable(int limit) { throw new AssertionError(); }

        @Override
        public List<ImportDelivery> findRetentionCandidates(
                ImportTerminalRetentionTarget target, Instant now, int limit) {
            return List.of(delivery);
        }

        @Override
        public boolean purgeTerminal(ImportDeliveryId deliveryId, long expectedVersion) {
            order.add("ledger");
            return true;
        }
    }
}
