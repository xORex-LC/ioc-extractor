package com.iocextractor.application.port.in.ingest;

/** Side-effect-free canonical preparation of an already claimed document. */
@FunctionalInterface
public interface PrepareIngestionUseCase {
    PreparedIngestion prepare(IngestSourceCommand command);
}
