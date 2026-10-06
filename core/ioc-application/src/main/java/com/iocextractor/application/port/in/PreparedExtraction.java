package com.iocextractor.application.port.in;

/** Owned sealed precommit state. Only promotion reserves IDs or changes canonical truth. */
public interface PreparedExtraction extends AutoCloseable {
    ExtractionResult promote();
    @Override
    void close();
}
