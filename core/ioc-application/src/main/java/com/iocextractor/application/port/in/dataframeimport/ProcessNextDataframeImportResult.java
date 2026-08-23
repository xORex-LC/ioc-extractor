package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;

import java.util.Objects;
import java.util.Optional;

/** Result of one coalesced attempt to advance only the durable queue head. */
public record ProcessNextDataframeImportResult(
        boolean workPerformed,
        Optional<ImportDeliveryId> deliveryId) {

    /** Enforces a delivery identity exactly when work was performed. */
    public ProcessNextDataframeImportResult {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (workPerformed != deliveryId.isPresent()) {
            throw new IllegalArgumentException("Performed import work must identify its delivery");
        }
    }
}
