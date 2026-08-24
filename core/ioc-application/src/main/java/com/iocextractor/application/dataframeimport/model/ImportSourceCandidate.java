package com.iocextractor.application.dataframeimport.model;

import java.time.Instant;
import java.util.Objects;

/** Stable transport-owned candidate discovered by a complete source listing. */
public record ImportSourceCandidate(
        ImportSourceId sourceId,
        String candidateToken,
        Instant detectedAt) {

    /** Requires a safe opaque token and detection time. */
    public ImportSourceCandidate {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(candidateToken, "candidateToken");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (candidateToken.isBlank()) {
            throw new IllegalArgumentException("Import candidate token must not be blank");
        }
    }
}
