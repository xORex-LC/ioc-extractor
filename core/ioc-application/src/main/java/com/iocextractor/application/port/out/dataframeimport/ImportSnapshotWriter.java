package com.iocextractor.application.port.out.dataframeimport;

import java.io.IOException;
import java.nio.file.Path;

/** Transport-owned bounded writer into a store-owned unpublished snapshot path. */
@FunctionalInterface
public interface ImportSnapshotWriter {

    /** Writes exact claimed bytes and completes source before/after evidence checks. */
    void write(Path unpublishedTarget) throws IOException;
}
