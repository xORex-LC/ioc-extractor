package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;

import java.util.Objects;

/** Forward-only request to archive or quarantine transport-owned source state. */
public record DispositionImportSourceCommand(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        ImportTerminalOutcome outcome) {

    /** Requires delivery and terminal outcome. */
    public DispositionImportSourceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(outcome, "outcome");
    }
}
