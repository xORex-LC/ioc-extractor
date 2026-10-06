package com.iocextractor.application.port.in.ingest;

/** Owned preparation whose serial promotion retains source-key exclusion and terminal CAS. */
public interface PreparedIngestion extends AutoCloseable {
    IngestSourceResult promote();
    @Override
    void close();
}
