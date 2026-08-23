package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.util.Objects;

/** Strict streaming read request using an already selected library-neutral dialect. */
public record DelimitedReadCommand(
        ImportSnapshotReference snapshotReference,
        String charset,
        DataframeImportCatalogDraft.Dialect dialect,
        DataframeImportCatalogDraft.Recognition recognition) {

    /** Requires all parse-boundary values. */
    public DelimitedReadCommand {
        Objects.requireNonNull(snapshotReference, "snapshotReference");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(recognition, "recognition");
    }
}
