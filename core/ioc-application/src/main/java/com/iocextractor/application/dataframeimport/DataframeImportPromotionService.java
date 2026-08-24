package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryTransition;
import com.iocextractor.application.dataframeimport.model.ImportLedgerTransitionResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ProcessNextDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportCommand;
import com.iocextractor.application.port.out.dataframeimport.CanonicalImportWriter;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Advances only a staged/promoting durable head across the dataframe receipt boundary. */
public final class DataframeImportPromotionService implements ProcessNextDataframeImportUseCase {

    private final ImportDeliveryLedger ledger;
    private final CanonicalImportWriter writer;
    private final Clock clock;

    /** Creates the framework-free forward-only promotion orchestrator. */
    public DataframeImportPromotionService(
            ImportDeliveryLedger ledger,
            CanonicalImportWriter writer,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProcessNextDataframeImportResult processNext() {
        Optional<ImportDelivery> due = ledger.findDueHead(clock.instant());
        if (due.isEmpty()) {
            return idle();
        }
        ImportDelivery delivery = due.orElseThrow();
        if (delivery.state() == ImportDeliveryState.STAGED) {
            ImportLedgerTransitionResult transition = ledger.transition(new ImportDeliveryTransition(
                    delivery.id(), ImportDeliveryState.STAGED, delivery.version(),
                    ImportDeliveryState.PROMOTING, Optional.empty(), clock.instant()));
            if (transition != ImportLedgerTransitionResult.APPLIED
                    && transition != ImportLedgerTransitionResult.ALREADY_APPLIED) {
                return idle();
            }
            delivery = ledger.find(delivery.id()).orElseThrow(
                    () -> new IllegalStateException("Promoting import delivery disappeared"));
        }
        if (delivery.state() != ImportDeliveryState.PROMOTING) {
            return idle();
        }

        writer.promote(command(delivery));
        ImportLedgerTransitionResult committed = ledger.transition(new ImportDeliveryTransition(
                delivery.id(), ImportDeliveryState.PROMOTING, delivery.version(),
                ImportDeliveryState.CANONICAL_COMMITTED, Optional.empty(), clock.instant()));
        if (committed != ImportLedgerTransitionResult.APPLIED
                && committed != ImportLedgerTransitionResult.ALREADY_APPLIED) {
            throw new IllegalStateException("Canonical import receipt could not advance the service ledger");
        }
        return new ProcessNextDataframeImportResult(true, Optional.of(delivery.id()));
    }

    private CanonicalImportCommand command(ImportDelivery delivery) {
        return new CanonicalImportCommand(
                delivery.id(), delivery.sequence(), delivery.sourceId(),
                delivery.snapshot().orElseThrow(
                        () -> new IllegalStateException("Promoting import has no pinned snapshot")),
                delivery.contract().orElseThrow(
                        () -> new IllegalStateException("Promoting import has no pinned contract")),
                delivery.stage().orElseThrow(
                        () -> new IllegalStateException("Promoting import has no pinned stage")));
    }

    private ProcessNextDataframeImportResult idle() {
        return new ProcessNextDataframeImportResult(false, Optional.empty());
    }
}
