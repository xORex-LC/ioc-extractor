package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.Objects;

/** Transport-neutral request to obtain ownership and pin one delivery's exact bytes. */
public record ClaimImportSourceCommand(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        String candidateToken) {

    /** Requires all managed-source identities. */
    public ClaimImportSourceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(candidateToken, "candidateToken");
        if (candidateToken.isBlank()) {
            throw new IllegalArgumentException("Import candidate token must not be blank");
        }
    }
}
