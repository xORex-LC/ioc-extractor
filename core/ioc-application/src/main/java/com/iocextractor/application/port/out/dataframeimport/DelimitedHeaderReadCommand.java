package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.DelimitedDialect;
import com.iocextractor.application.dataframeimport.model.DelimitedInputLimits;
import com.iocextractor.application.dataframeimport.model.ImportSnapshotReference;

import java.util.Objects;

/** Header-only strict parse request used for exact-one structural recognition. */
public record DelimitedHeaderReadCommand(
        ImportSnapshotReference snapshotReference,
        String charset,
        DelimitedDialect dialect,
        DelimitedInputLimits limits) {

    /** Requires all parser-boundary values. */
    public DelimitedHeaderReadCommand {
        Objects.requireNonNull(snapshotReference, "snapshotReference");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(limits, "limits");
    }
}
