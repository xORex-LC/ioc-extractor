package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportDelivery;

import java.util.Objects;

/** Result of idempotently admitting one source candidate. */
public record AdmitDataframeImportResult(ImportDelivery delivery, boolean newlyReserved) {

    /** Requires the durable delivery aggregate. */
    public AdmitDataframeImportResult {
        Objects.requireNonNull(delivery, "delivery");
    }
}
