package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportManagedObjectId;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportTerminalOutcome;

import java.util.Objects;

/** Path-free request to purge one transport-managed terminal source object. */
public record PurgeImportTerminalSourceCommand(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        ImportManagedObjectId managedObjectId,
        ImportTerminalOutcome expectedOutcome) {

    /** Validates the complete source-remnant identity. */
    public PurgeImportTerminalSourceCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(managedObjectId, "managedObjectId");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome");
        if (!managedObjectId.equals(ImportManagedObjectId.from(deliveryId))) {
            throw new IllegalArgumentException("Managed-object ID does not belong to delivery");
        }
    }
}
