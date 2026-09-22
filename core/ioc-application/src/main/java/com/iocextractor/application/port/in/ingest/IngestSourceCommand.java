package com.iocextractor.application.port.in.ingest;

import com.iocextractor.application.artifact.lifecycle.ObservationId;
import com.iocextractor.application.ingest.SourceKey;
import com.iocextractor.application.ingest.SourceUnit;
import com.iocextractor.application.observation.RegisteredObservation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Command to process one source file detected by an inbound adapter.
 *
 * @param source original source path
 * @param observationId durable identity of this delivered occurrence
 * @param key source content key
 * @param detectedAt adapter observation timestamp
 * @param claimedSource already-owned source for ordered admission, or {@code null}
 * @param registration durable delivery precedence, or {@code null} for legacy artifact policies
 */
public record IngestSourceCommand(Path source,
                                  ObservationId observationId,
                                  SourceKey key,
                                  Instant detectedAt,
                                  SourceUnit claimedSource,
                                  RegisteredObservation registration) {

    public IngestSourceCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (claimedSource != null && (!claimedSource.observationId().equals(observationId)
                || !claimedSource.key().equals(key))) {
            throw new IllegalArgumentException("Claimed source does not match ingest occurrence and key");
        }
        if (registration != null && !registration.observationId().equals(observationId)) {
            throw new IllegalArgumentException("Registration does not match ingest occurrence");
        }
    }

    public IngestSourceCommand(Path source, ObservationId observationId,
                               SourceKey key, Instant detectedAt) {
        this(source, observationId, key, detectedAt, null, null);
    }

    public IngestSourceCommand(SourceUnit claimedSource, RegisteredObservation registration) {
        this(claimedSource.originalPath(), claimedSource.observationId(), claimedSource.key(),
                claimedSource.detectedAt(), claimedSource, registration);
    }

    /** Compatibility constructor for a recoverable delivery identified by its content key. */
    public IngestSourceCommand(Path source, SourceKey key, Instant detectedAt) {
        this(source, ObservationId.legacy(key.value()), key, detectedAt, null, null);
    }

    public Optional<SourceUnit> claimedSourceOptional() {
        return Optional.ofNullable(claimedSource);
    }

    public Optional<RegisteredObservation> registrationOptional() {
        return Optional.ofNullable(registration);
    }
}
