package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.SourceSinks;
import com.iocextractor.application.ingest.SourceUnit;

/**
 * Creates output sinks scoped to one claimed source.
 */
public interface SourceSinkFactory {

    /**
     * Builds sink instances for a claimed source.
     *
     * @param source claimed source
     * @return source-scoped sink set
     */
    SourceSinks createFor(SourceUnit source);
}
