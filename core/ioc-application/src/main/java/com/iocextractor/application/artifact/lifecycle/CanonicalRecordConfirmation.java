package com.iocextractor.application.artifact.lifecycle;

import com.iocextractor.application.artifact.ArtifactRowKey;
import com.iocextractor.application.artifact.PreparedArtifactRow;

import java.util.Objects;

/**
 * One identity-resolved prepared row accepted for canonical confirmation.
 *
 * @param rowKey stable canonical identity within the artifact
 * @param preparedRow business-row template with an optional deferred public id
 */
public record CanonicalRecordConfirmation(ArtifactRowKey rowKey,
                                          PreparedArtifactRow preparedRow) {

    /** Validates the confirmation payload. */
    public CanonicalRecordConfirmation {
        Objects.requireNonNull(rowKey, "rowKey");
        Objects.requireNonNull(preparedRow, "preparedRow");
    }
}
