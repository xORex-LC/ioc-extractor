package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalRetentionTarget;
import com.iocextractor.application.maintenance.RetentionAction;
import com.iocextractor.application.port.in.dataframeimport.RunDataframeImportRetentionUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportCommitEvidenceStore;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalRetentionStore;
import com.iocextractor.application.port.out.dataframeimport.ImportTerminalSourceRetention;
import com.iocextractor.application.port.out.dataframeimport.ImportSnapshotStore;
import com.iocextractor.application.port.out.dataframeimport.ImportWorkspace;
import com.iocextractor.application.port.out.dataframeimport.PurgeImportTerminalSourceCommand;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Bounded cleanup that keeps the service ledger until every external purge succeeds. */
public final class DataframeImportRetentionService implements RunDataframeImportRetentionUseCase {

    private final ImportDeliveryLedger ledger;
    private final ImportTerminalRetentionStore terminals;
    private final ImportTerminalSourceRetention sourceRetention;
    private final ImportSnapshotStore snapshots;
    private final ImportWorkspace workspace;
    private final ImportCommitEvidenceStore commits;
    private final Clock clock;
    private final List<ImportTerminalRetentionTarget> targets;

    /** Creates independently schedulable import retention. */
    public DataframeImportRetentionService(ImportDeliveryLedger ledger,
                                           ImportTerminalRetentionStore terminals,
                                           ImportTerminalSourceRetention sourceRetention,
                                           ImportSnapshotStore snapshots,
                                           ImportWorkspace workspace,
                                           ImportCommitEvidenceStore commits,
                                           Clock clock,
                                           List<ImportTerminalRetentionTarget> targets) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.sourceRetention = Objects.requireNonNull(sourceRetention, "sourceRetention");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        requireDisjointOutcomes(this.targets);
    }

    @Override
    public int retain(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Import retention limit must be positive");
        }
        int purged = 0;
        int remaining = limit;
        for (ImportTerminalRetentionTarget target : targets) {
            if (remaining == 0) {
                break;
            }
            for (ImportDelivery delivery : ledger.findRetentionCandidates(
                    target, clock.instant(), remaining)) {
                retainTerminal(target, delivery);
                purged++;
                remaining--;
            }
        }
        return purged;
    }

    private void retainTerminal(ImportTerminalRetentionTarget target, ImportDelivery delivery) {
        if (delivery.sourceOccurrenceKind()
                == com.iocextractor.application.dataframeimport.model.ImportSourceOccurrenceKind.FORWARD) {
            sourceRetention.purge(new PurgeImportTerminalSourceCommand(
                    delivery.id(), delivery.sourceId(), ImportManagedObjectId.from(delivery.id()),
                    delivery.terminalOutcome().orElseThrow()));
        }
        if (target.action() == RetentionAction.ARCHIVE) {
            terminals.archive(delivery.id(), target.archiveDirectory());
        } else {
            terminals.delete(delivery.id());
        }
        workspace.discard(delivery.id());
        snapshots.purge(delivery.id());
        commits.purge(delivery.id());
        if (!ledger.purgeTerminal(delivery.id(), delivery.version())) {
            throw new DataframeImportConsistencyException(
                    "Import terminal state changed during retention cleanup");
        }
    }

    private void requireDisjointOutcomes(List<ImportTerminalRetentionTarget> configuredTargets) {
        var seen = java.util.EnumSet.noneOf(
                com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome.class);
        for (ImportTerminalRetentionTarget target : configuredTargets) {
            for (var outcome : target.outcomes()) {
                if (!seen.add(outcome)) {
                    throw new IllegalArgumentException(
                            "Import retention outcome belongs to more than one target: " + outcome);
                }
            }
        }
    }
}
