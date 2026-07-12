package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.SourcePreparers;
import com.iocextractor.application.ingest.SourceUnit;

/** Creates side-effect-free artifact preparers scoped to one claimed source. */
public interface SourcePreparerFactory {

    /** Builds preparers carrying the claimed source identity. */
    SourcePreparers createFor(SourceUnit source);
}
