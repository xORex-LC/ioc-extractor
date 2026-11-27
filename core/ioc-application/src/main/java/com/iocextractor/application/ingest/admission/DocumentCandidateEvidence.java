package com.iocextractor.application.ingest.admission;

import java.util.Objects;
import java.util.Optional;

/** Filesystem evidence captured without reading IOC payload bytes. */
public record DocumentCandidateEvidence(Optional<String> fileKey,
                                        long size,
                                        long modifiedAtNanos) {

    public DocumentCandidateEvidence {
        fileKey = Objects.requireNonNull(fileKey, "fileKey")
                .filter(value -> !value.isBlank());
        if (size < 0 || modifiedAtNanos < 0) {
            throw new IllegalArgumentException("Document candidate metadata must be nonnegative");
        }
    }

    /** A missing stable identity is ambiguous and therefore never inherits a reservation. */
    public boolean sameObjectAs(DocumentCandidateEvidence claimed) {
        Objects.requireNonNull(claimed, "claimed");
        return sameFileAs(claimed) && size == claimed.size && modifiedAtNanos == claimed.modifiedAtNanos;
    }

    /** Identifies the claimed inode even if its metadata changed after ownership moved. */
    public boolean sameFileAs(DocumentCandidateEvidence other) {
        return fileKey.isPresent() && fileKey.equals(other.fileKey());
    }
}
