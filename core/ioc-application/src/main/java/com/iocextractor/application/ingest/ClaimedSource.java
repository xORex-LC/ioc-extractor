package com.iocextractor.application.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Private hash-independent file ownership acquired for one registered occurrence. */
public record ClaimedSource(ObservationId observationId,
                            Path originalPath,
                            Path processingPath,
                            Instant detectedAt) {

    public ClaimedSource {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(originalPath, "originalPath");
        Objects.requireNonNull(processingPath, "processingPath");
        Objects.requireNonNull(detectedAt, "detectedAt");
    }
}
