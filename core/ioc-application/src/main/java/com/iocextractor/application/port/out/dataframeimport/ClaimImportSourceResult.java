package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSnapshot;

import java.util.Objects;

/** Result proving transport ownership and a durable private immutable local snapshot. */
public record ClaimImportSourceResult(ImportSnapshot snapshot) {

    /** Requires complete snapshot evidence. */
    public ClaimImportSourceResult {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
