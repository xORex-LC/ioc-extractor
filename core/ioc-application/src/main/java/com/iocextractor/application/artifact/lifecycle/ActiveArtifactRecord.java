package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.artifact.ArtifactRow;
import com.iocextractor.application.artifact.ArtifactRowKey;

import java.util.Objects;

/**
 * Public business row paired with its internal active lifecycle facts.
 *
 * @param rowKey canonical row identity
 * @param row ordered public row values
 * @param lifecycle internal lifecycle facts
 */
public record ActiveArtifactRecord(ArtifactRowKey rowKey,
                                   ArtifactRow row,
                                   RecordLifecycle lifecycle) {

    /** Validates the active record payload. */
    public ActiveArtifactRecord {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }
}
