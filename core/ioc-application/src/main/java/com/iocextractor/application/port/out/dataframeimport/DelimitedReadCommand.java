package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.contract.DataframeImportCatalogDraft;
import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.util.Objects;

/** Strict streaming read request using an already selected library-neutral dialect. */
public record DelimitedReadCommand(
        ImportSnapshotReference snapshotReference,
        String charset,
        DelimitedDialect dialect,
        DataframeImportCatalogDraft.Recognition recognition,
        DelimitedInputLimits limits) {

    /** Requires all parse-boundary values. */
    public DelimitedReadCommand {
        Objects.requireNonNull(snapshotReference, "snapshotReference");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(recognition, "recognition");
        Objects.requireNonNull(limits, "limits");
    }

    /** Creates a read command with conservative default hard limits. */
    public DelimitedReadCommand(ImportSnapshotReference snapshotReference,
                                String charset,
                                DelimitedDialect dialect,
                                DataframeImportCatalogDraft.Recognition recognition) {
        this(snapshotReference, charset, dialect, recognition, DelimitedInputLimits.defaults());
    }
}
