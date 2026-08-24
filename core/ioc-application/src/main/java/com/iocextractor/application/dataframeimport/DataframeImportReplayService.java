package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportClaimReservation;
import com.iocextractor.application.dataframeimport.model.ImportDelivery;
import com.iocextractor.application.dataframeimport.model.ImportDeliveryState;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.AdmitDataframeImportUseCase;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportCommand;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportResult;
import com.iocextractor.application.port.in.dataframeimport.ReplayDataframeImportUseCase;
import com.iocextractor.application.port.out.dataframeimport.ImportDeliveryLedger;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Creates a new ordered occurrence from retained terminal evidence. */
public final class DataframeImportReplayService implements ReplayDataframeImportUseCase {

    private final ImportDeliveryLedger ledger;
    private final AdmitDataframeImportUseCase admission;
    private final Clock clock;

    /** Creates a replay service whose materialization is owned by admission recovery. */
    public DataframeImportReplayService(ImportDeliveryLedger ledger,
                                        AdmitDataframeImportUseCase admission,
                                        Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReplayDataframeImportResult replay(ReplayDataframeImportCommand command) {
        Objects.requireNonNull(command, "command");
        ImportDelivery terminal = ledger.find(command.terminalDeliveryId()).orElseThrow(
                () -> new IllegalArgumentException("Terminal import delivery does not exist"));
        if (terminal.state() != ImportDeliveryState.TERMINAL) {
            throw new IllegalArgumentException("Only a terminal import delivery can be replayed");
        }
        String candidateToken = "replay:" + terminal.id().value() + ":" + command.newDeliveryId().value();
        ImportClaimReservation reservation = new ImportClaimReservation(
                command.newDeliveryId(), terminal.sourceId(), candidateToken,
                Optional.of(terminal.id()), clock.instant());
        return new ReplayDataframeImportResult(
                admission.admit(new AdmitDataframeImportCommand(reservation)).delivery());
    }
}
