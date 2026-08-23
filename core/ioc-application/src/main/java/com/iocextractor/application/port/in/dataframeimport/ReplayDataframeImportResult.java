package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;

import java.util.Objects;

/** Result containing the newly reserved replay delivery. */
public record ReplayDataframeImportResult(ImportDelivery delivery) {

    /** Requires the new replay occurrence. */
    public ReplayDataframeImportResult {
        Objects.requireNonNull(delivery, "delivery");
    }
}
