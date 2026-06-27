package com.iocextractor.application.port.out.export;

import com.iocextractor.application.export.SnapshotMetadata;
import com.iocextractor.application.export.SnapshotRequest;

/** Driven port for streaming a strict multi-artifact canonical read snapshot. */
public interface SnapshotSliceReader {

    /**
     * Calls {@code consumer} synchronously while one storage snapshot remains open.
     *
     * @return the exact metadata observed by the callbacks
     */
    SnapshotMetadata stream(SnapshotRequest request, SnapshotRowConsumer consumer);
}
