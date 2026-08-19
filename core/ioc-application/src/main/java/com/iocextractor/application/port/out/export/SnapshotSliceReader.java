package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;

/**
 * Driven port for resolving export-owned projection state and streaming one
 * strict multi-artifact canonical snapshot.
 */
public interface SnapshotSliceReader {

    /**
     * Resolves any durable export mapping before callbacks, then calls
     * {@code consumer} synchronously while one storage snapshot remains open.
     *
     * @return the exact metadata observed by the callbacks
     */
    SnapshotMetadata stream(SnapshotRequest request, SnapshotRowConsumer consumer);
}
