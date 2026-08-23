package com.iocextractor.application.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDeliveryId;
import com.iocextractor.application.dataframeimport.model.ImportSnapshot;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.Objects;

/** Pinned delivery inputs required to recognize, map and stage one snapshot. */
public record ImportStagingCommand(
        ImportDeliveryId deliveryId,
        ImportSourceId sourceId,
        ImportSnapshot snapshot) {

    /** Requires complete delivery and immutable snapshot identity. */
    public ImportStagingCommand {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
