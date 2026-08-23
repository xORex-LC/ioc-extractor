package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;

import java.util.Objects;

/** Request to create a new delivery causally linked to a protected terminal unit. */
public record ReplayDataframeImportCommand(
        ImportDeliveryId terminalDeliveryId,
        ImportDeliveryId newDeliveryId) {

    /** Requires distinct old and new occurrence identities. */
    public ReplayDataframeImportCommand {
        Objects.requireNonNull(terminalDeliveryId, "terminalDeliveryId");
        Objects.requireNonNull(newDeliveryId, "newDeliveryId");
        if (terminalDeliveryId.equals(newDeliveryId)) {
            throw new IllegalArgumentException("Replay must create a new import delivery identity");
        }
    }
}
