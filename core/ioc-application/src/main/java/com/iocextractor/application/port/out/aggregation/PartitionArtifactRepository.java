package com.iocextractor.application.port.out.aggregation;

import com.iocextractor.application.aggregation.PartitionArtifact;
import com.iocextractor.application.ingest.IngestionRecord;

import java.util.List;

/**
 * Driven port for reading source-scoped partition artifacts.
 */
public interface PartitionArtifactRepository {

    List<PartitionArtifact> readPartitions(List<IngestionRecord> records);
}
