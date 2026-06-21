package com.iocextractor.application.port.out.ingest;

import com.iocextractor.application.ingest.PartitionSinks;
import com.iocextractor.application.ingest.SourceUnit;

/**
 * Creates output sinks scoped to one source partition.
 */
public interface PartitionSinkFactory {

    /**
     * Builds sink instances and paths for a claimed source.
     *
     * @param source claimed source
     * @return partition sink set
     */
    PartitionSinks createFor(SourceUnit source);
}
