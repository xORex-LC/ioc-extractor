package com.iocextractor.application.port.out.dataframeimport;

import com.iocextractor.application.dataframeimport.model.ImportSourceId;
import com.iocextractor.application.dataframeimport.model.ImportSourceReadiness;

/** Driven port for a positive, source-scoped managed-import capability check. */
@FunctionalInterface
public interface ImportSourceCapability {

    /** Probes one configured source without admitting a producer candidate. */
    ImportSourceReadiness probe(ImportSourceId sourceId);
}
