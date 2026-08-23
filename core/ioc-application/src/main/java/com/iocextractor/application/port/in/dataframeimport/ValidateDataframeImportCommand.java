package com.iocextractor.application.port.in.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;
import com.iocextractor.application.dataframeimport.model.ImportSourceId;

import java.util.Objects;

/** Advisory validation request over caller-owned bytes; it grants no reservation or write authority. */
public record ValidateDataframeImportCommand(
        ImportSourceId sourceId,
        ImportSnapshotReference snapshotReference) {

    /** Requires source and snapshot identities. */
    public ValidateDataframeImportCommand {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(snapshotReference, "snapshotReference");
    }
}
